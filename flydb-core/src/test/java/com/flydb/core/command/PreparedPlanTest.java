package com.flydb.core.command;

import java.nio.file.Files;
import java.nio.file.Path;
import com.flydb.core.Flydb;
import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.PreparedMigrationPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PreparedPlanTest {
    @TempDir Path directory;

    @Test void rejectsChangedSqlAndChangedPendingSet() throws Exception {
        Path script = directory.resolve("V1__one.sql");
        Files.write(script, "SELECT 1;".getBytes("UTF-8"));
        InMemoryFlydbDataSource db = new InMemoryFlydbDataSource(false);
        Flydb flydb = create(db);
        PreparedMigrationPlan plan = flydb.prepareMigrate();
        Files.write(script, "SELECT 2;".getBytes("UTF-8"));
        assertThatThrownBy(() -> flydb.migrate(plan)).hasMessageContaining("FLYDB-2011");
        assertThat(db.executedSql()).doesNotContain("SELECT 2");
        PreparedMigrationPlan current = flydb.prepareMigrate();
        flydb.migrate(current);
        assertThatThrownBy(() -> flydb.migrate(current)).hasMessageContaining("FLYDB-2011");
    }

    @Test void pinsTimestampToPreviewAndExecutesTheCheckedStatements() throws Exception {
        Files.write(directory.resolve("V1__stamp.sql"),
                "SELECT '${flydb:timestamp}';".getBytes("UTF-8"));
        InMemoryFlydbDataSource db = new InMemoryFlydbDataSource(false);
        Flydb flydb = create(db);
        PreparedMigrationPlan plan = flydb.prepareMigrate();
        flydb.migrate(plan);
        assertThat(db.executedSql()).contains(plan.preview().migrations().get(0).statements().get(0).sql());
    }

    private Flydb create(InMemoryFlydbDataSource db) {
        return new Flydb(FlydbConfiguration.builder().dataSource(db)
                .locations("filesystem:" + directory).build());
    }

    @Test void rejectsSameSqlAgainstADifferentTarget() throws Exception {
        Files.write(directory.resolve("V1__one.sql"), "SELECT 1;".getBytes("UTF-8"));
        PreparedMigrationPlan plan = create(new InMemoryFlydbDataSource(false)).prepareMigrate();
        InMemoryFlydbDataSource other = new InMemoryFlydbDataSource(true);
        assertThatThrownBy(() -> create(other).migrate(plan)).hasMessageContaining("FLYDB-2011");
        assertThat(other.executedSql()).doesNotContain("SELECT 1");
    }
}
