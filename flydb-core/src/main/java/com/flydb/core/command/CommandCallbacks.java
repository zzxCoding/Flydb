package com.flydb.core.command;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.callback.Callback;
import com.flydb.core.callback.CallbackDispatcher;
import com.flydb.core.callback.Event;
import com.flydb.core.dialect.Database;
import com.flydb.core.executor.SqlMigrationExecutor;

/** 显式、SPI 与 SQL 文件三类回调的命令级聚合。 */
final class CommandCallbacks {

    private final CallbackDispatcher dispatcher;
    private final Callback.Context context;

    private CommandCallbacks(CallbackDispatcher dispatcher, Callback.Context context) {
        this.dispatcher = dispatcher;
        this.context = context;
    }

    static CommandCallbacks create(CommandRuntime runtime) {
        List<Callback> callbacks = new ArrayList<Callback>(runtime.configuration().callbacks());
        for (Callback callback : ServiceLoader.load(Callback.class,
                runtime.configuration().classLoader())) {
            callbacks.add(callback);
        }
        callbacks.addAll(sqlCallbacks(runtime.configuration(), runtime.database()));
        Callback.Context context = new DefaultContext(runtime.connection(), runtime.configuration());
        return new CommandCallbacks(new CallbackDispatcher(callbacks), context);
    }

    void fire(Event event) {
        dispatcher.fire(event, context);
    }

    private static List<Callback> sqlCallbacks(FlydbConfiguration configuration,
                                               Database database) {
        List<Callback> result = new ArrayList<Callback>();
        for (Event event : Event.values()) {
            String script = eventScript(event);
            String sql = ScriptLoader.loadIfExists(configuration, script);
            if (sql != null) {
                result.add(new SqlEventCallback(event, script, sql, database));
            }
        }
        return result;
    }

    private static String eventScript(Event event) {
        String[] words = event.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            name.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return name.append(".sql").toString();
    }

    private static final class SqlEventCallback implements Callback {
        private final Event supportedEvent;
        private final String script;
        private final String sql;
        private final Database database;

        SqlEventCallback(Event supportedEvent, String script, String sql, Database database) {
            this.supportedEvent = supportedEvent;
            this.script = script;
            this.sql = sql;
            this.database = database;
        }

        @Override
        public boolean supports(Event event, Context context) {
            return event == supportedEvent;
        }

        @Override
        public void handle(Event event, Context context) {
            try {
                new SqlMigrationExecutor(script, sql, database.statementBuilderConfig(),
                        context.configuration().placeholderReplacement(),
                        context.configuration().placeholderPrefix(),
                        context.configuration().placeholderSuffix(),
                        context.configuration().placeholders(),
                        CommandRuntime.builtIns(database, context.configuration()))
                        .execute(context.connection());
            } catch (java.sql.SQLException e) {
                throw new com.flydb.core.exception.FlydbException(
                        com.flydb.core.exception.ErrorCode.MIGRATION_EXECUTION_FAILED,
                        "SQL 回调 " + script + " 执行失败: " + e.getMessage(), e);
            }
        }
    }

    private static final class DefaultContext implements Callback.Context {
        private final Connection connection;
        private final FlydbConfiguration configuration;

        DefaultContext(Connection connection, FlydbConfiguration configuration) {
            this.connection = connection;
            this.configuration = configuration;
        }

        @Override public Connection connection() { return connection; }
        @Override public FlydbConfiguration configuration() { return configuration; }
    }
}
