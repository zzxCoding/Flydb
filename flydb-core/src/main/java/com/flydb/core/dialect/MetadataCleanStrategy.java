package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 基于 JDBC metadata 的 PG/MySQL schema 清理实现。 */
final class MetadataCleanStrategy implements CleanStrategy {

    private final char quote;
    private final boolean cascade;
    private final boolean disableForeignKeys;
    private final boolean sequences;

    MetadataCleanStrategy(char quote, boolean cascade, boolean disableForeignKeys,
                          boolean sequences) {
        this.quote = quote;
        this.cascade = cascade;
        this.disableForeignKeys = disableForeignKeys;
        this.sequences = sequences;
    }

    @Override
    public void clean(Connection connection, String schema, List<String> excludedTables)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> views = objects(metadata, schema, new String[]{"VIEW"});
        List<String> tables = objects(metadata, schema, new String[]{"TABLE"});
        tables.removeAll(excludedTables);
        List<String> sequenceNames = sequences ? sequences(connection, schema)
                : new ArrayList<String>();
        Statement statement = connection.createStatement();
        try {
            if (disableForeignKeys) statement.execute("SET FOREIGN_KEY_CHECKS=0");
            dropAll(statement, "VIEW", views);
            dropAll(statement, "TABLE", tables);
            dropAll(statement, "SEQUENCE", sequenceNames);
        } finally {
            if (disableForeignKeys) {
                try { statement.execute("SET FOREIGN_KEY_CHECKS=1"); } catch (SQLException ignored) { }
            }
            statement.close();
        }
    }

    private List<String> objects(DatabaseMetaData metadata, String schema,
                                 String[] types) throws SQLException {
        List<String> result = new ArrayList<String>();
        ResultSet rs = disableForeignKeys
                ? metadata.getTables(schema, null, "%", types)
                : metadata.getTables(null, schema, "%", types);
        try {
            while (rs.next()) result.add(rs.getString("TABLE_NAME"));
        } finally {
            rs.close();
        }
        return result;
    }

    private static List<String> sequences(Connection connection, String schema)
            throws SQLException {
        List<String> result = new ArrayList<String>();
        java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = ?");
        statement.setString(1, schema);
        ResultSet rs = statement.executeQuery();
        try {
            while (rs.next()) result.add(rs.getString(1));
        } finally {
            rs.close();
            statement.close();
        }
        return result;
    }

    private void dropAll(Statement statement, String kind, List<String> objects)
            throws SQLException {
        for (String object : objects) {
            statement.execute("DROP " + kind + " " + quoted(object)
                    + (cascade ? " CASCADE" : ""));
        }
    }

    private String quoted(String identifier) {
        String q = String.valueOf(quote);
        return q + identifier.replace(q, q + q) + q;
    }
}
