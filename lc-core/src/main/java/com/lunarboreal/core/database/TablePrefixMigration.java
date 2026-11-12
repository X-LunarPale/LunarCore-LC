package com.lunarboreal.core.database;

import com.lunarboreal.api.database.Database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TablePrefixMigration {
    private static final String LEGACY = "l" + "br_";
    private static final String CURRENT = "lc_";

    private TablePrefixMigration() {}

    static String migrate(Connection connection, Database.Type type, String prefix) throws SQLException {
        if (!prefix.equals(LEGACY) && !prefix.equals(CURRENT)) return prefix;
        Set<String> tables = new HashSet<>();
        try (ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (result.next()) tables.add(result.getString("TABLE_NAME"));
        }
        List<String> oldTables = tables.stream().filter(name -> name.startsWith(LEGACY)).sorted().toList();
        for (String old : oldTables) {
            if (tables.contains(CURRENT + old.substring(LEGACY.length()))) {
                throw new SQLException("新旧数据库表同时存在，请先合并数据: " + old);
            }
        }
        if (oldTables.isEmpty()) return CURRENT;
        if (type == Database.Type.MYSQL) {
            List<String> renames = new ArrayList<>();
            for (String old : oldTables) {
                renames.add(quote(old) + " TO " + quote(CURRENT + old.substring(LEGACY.length())));
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("RENAME TABLE " + String.join(", ", renames));
            }
        } else {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String old : oldTables) {
                    statement.executeUpdate("ALTER TABLE " + quote(old) + " RENAME TO "
                            + quote(CURRENT + old.substring(LEGACY.length())));
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
        return CURRENT;
    }

    private static String quote(String value) {
        return "`" + value.replace("`", "``") + "`";
    }
}
