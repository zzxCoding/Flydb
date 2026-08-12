package com.flydb.core;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.MigrationInfoService;
import com.flydb.core.command.InfoCommand;
import com.flydb.core.command.MigrateCommand;
import com.flydb.core.api.RepairResult;
import com.flydb.core.api.UndoResult;
import com.flydb.core.command.ValidateCommand;
import com.flydb.core.command.BaselineCommand;
import com.flydb.core.command.RepairCommand;
import com.flydb.core.command.CleanCommand;
import com.flydb.core.command.UndoCommand;

/**
 * Flydb 门面（设计 02 §1）。
 *
 * <p>门面自身无可变状态，可安全复用/并发调用。典型用法：
 * <pre>{@code
 * Flydb flydb = Flydb.configure()
 *     .url("jdbc:dm://localhost:5236")
 *     .user("SYSDBA")
 *     .password(System.getenv("DB_PASSWORD"))
 *     .locations("classpath:db/migration")
 *     .load();
 * flydb.migrate();
 * }</pre>
 */
public final class Flydb {

    private final FlydbConfiguration configuration;

    public Flydb(FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public static FlydbConfiguration.Builder configure() {
        return FlydbConfiguration.builder();
    }

    public MigrateResult migrate() {
        return new MigrateCommand(configuration).execute();
    }

    public MigrationInfoService info() {
        return new InfoCommand(configuration).execute();
    }

    public void validate() { new ValidateCommand(configuration).execute(); }
    public void baseline() { new BaselineCommand(configuration).execute(); }
    public RepairResult repair() { return new RepairCommand(configuration).execute(); }
    public void clean() { new CleanCommand(configuration).execute(); }
    public UndoResult undo() { return new UndoCommand(configuration).execute(); }
}
