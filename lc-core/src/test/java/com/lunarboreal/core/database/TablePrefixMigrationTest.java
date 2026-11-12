package com.lunarboreal.core.database;

import com.lunarboreal.api.database.Database;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class TablePrefixMigrationTest {
    private static final String LEGACY = "l" + "br_";

    @Test
    void migratesExistingRowsAndForeignKeysOnce() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("CREATE TABLE " + LEGACY + "players (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("CREATE TABLE " + LEGACY + "homes (player INTEGER REFERENCES " + LEGACY + "players(id))");
            statement.execute("INSERT INTO " + LEGACY + "players VALUES (1, 'Player')");
            statement.execute("INSERT INTO " + LEGACY + "homes VALUES (1)");
            assertEquals("lc_", TablePrefixMigration.migrate(connection, Database.Type.SQLITE, LEGACY));
            try (var result = statement.executeQuery("SELECT name FROM lc_players JOIN lc_homes ON lc_players.id=lc_homes.player")) {
                assertTrue(result.next());
                assertEquals("Player", result.getString(1));
            }
            assertEquals("lc_", TablePrefixMigration.migrate(connection, Database.Type.SQLITE, "lc_"));
            try (var result = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(result.next());
            }
        }
    }

    @Test
    void preservesCustomPrefix() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertEquals("custom_", TablePrefixMigration.migrate(connection, Database.Type.SQLITE, "custom_"));
        }
    }

    @Test
    void refusesConflictingTablesWithoutLosingData() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + LEGACY + "players (id INTEGER)");
            statement.execute("INSERT INTO " + LEGACY + "players VALUES (1)");
            statement.execute("CREATE TABLE lc_players (id INTEGER)");
            assertThrows(SQLException.class, () -> TablePrefixMigration.migrate(connection, Database.Type.SQLITE, LEGACY));
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + LEGACY + "players")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }
}
