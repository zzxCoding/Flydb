package sample;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class NightlyExport {
    public void export(Connection connection, String externallyConfiguredColumns) throws SQLException {
        String sql = "SELECT " + externallyConfiguredColumns + " FROM SALES.CUSTOMER";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) { System.out.println(rows.getObject(1)); }
        }
    }
}
