package com.flydb.core.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.flydb.core.Flydb;
import com.flydb.core.api.ExecutionEvent;
import com.flydb.core.api.FlydbConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutionObserverTest {
    @TempDir Path directory;

    @Test void reportsConfirmedStatementsSeparatelyFromTransactionCompletion() throws Exception {
        Files.write(directory.resolve("V1__data.sql"), "INSERT INTO demo VALUES (1);".getBytes("UTF-8"));
        List<ExecutionEvent> events = new ArrayList<ExecutionEvent>();
        new Flydb(FlydbConfiguration.builder().dataSource(new InMemoryFlydbDataSource(false))
                .locations("filesystem:" + directory).executionObserver(events::add).build()).migrate();
        assertThat(events).extracting(ExecutionEvent::type).containsSubsequence(
                ExecutionEvent.Type.SCRIPT_STARTED, ExecutionEvent.Type.SQL_PROGRESS,
                ExecutionEvent.Type.TRANSACTION_RESULT, ExecutionEvent.Type.SCRIPT_COMPLETED);
        assertThat(events).filteredOn(e -> e.type() == ExecutionEvent.Type.SQL_PROGRESS)
                .anySatisfy(e -> { assertThat(e.confirmed()).isEqualTo(1); assertThat(e.total()).isEqualTo(1); });
        assertThat(events).filteredOn(e -> e.type() == ExecutionEvent.Type.TRANSACTION_RESULT)
                .singleElement().satisfies(e -> assertThat(e.transaction()).isEqualTo("COMMITTED"));
    }

    @Test void observerFailureDoesNotChangeMigrationOutcome() throws Exception {
        Files.write(directory.resolve("V1__data.sql"), "SELECT 1;".getBytes("UTF-8"));
        Flydb flydb = new Flydb(FlydbConfiguration.builder().dataSource(new InMemoryFlydbDataSource(false))
                .locations("filesystem:" + directory)
                .executionObserver(event -> { throw new IllegalStateException("observer offline"); }).build());
        assertThat(flydb.migrate().executed()).containsExactly("V1__data.sql");
    }

    @Test void failureReportsRollbackWithoutReportingScriptCompletion() throws Exception {
        Files.write(directory.resolve("V1__broken.sql"), "INSERT INTO demo VALUES (1); BROKEN;".getBytes("UTF-8"));
        List<ExecutionEvent> events = new ArrayList<ExecutionEvent>();
        Flydb flydb = new Flydb(FlydbConfiguration.builder().dataSource(new InMemoryFlydbDataSource(true))
                .locations("filesystem:" + directory).executionObserver(events::add).build());
        org.assertj.core.api.Assertions.assertThatThrownBy(flydb::migrate).hasMessageContaining("FLYDB-2010");
        assertThat(events).extracting(ExecutionEvent::type).contains(ExecutionEvent.Type.SQL_FAILURE)
                .doesNotContain(ExecutionEvent.Type.SCRIPT_COMPLETED);
        assertThat(events).filteredOn(e -> e.type() == ExecutionEvent.Type.TRANSACTION_RESULT)
                .singleElement().satisfies(e -> assertThat(e.transaction()).isEqualTo("ROLLED_BACK"));
    }
}
