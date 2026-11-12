package com.lunarboreal.qqauth;

import com.lunarboreal.api.database.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Persists the last reminder date across reconnects and module restarts. */
final class DailyBindingReminders {
    private final Database database;
    private final String table;

    DailyBindingReminders(Database database) {
        this.database = Objects.requireNonNull(database);
        table = database.table("qqauth_binding_reminders");
    }

    void initialize() throws SQLException {
        database.update("CREATE TABLE IF NOT EXISTS " + table
                + " (uuid VARCHAR(36) PRIMARY KEY, reminder_day VARCHAR(10) NOT NULL)");
    }

    synchronized boolean claim(UUID uuid, LocalDate day) throws SQLException {
        return database.transaction(connection -> {
            String previous = null;
            try (var query = connection.prepareStatement("SELECT reminder_day FROM " + table + " WHERE uuid=?")) {
                query.setString(1, uuid.toString());
                try (var rows = query.executeQuery()) {
                    if (rows.next()) previous = rows.getString(1);
                }
            }
            if (day.toString().equals(previous)) return false;
            String sql = previous == null
                    ? "INSERT INTO " + table + " (reminder_day,uuid) VALUES (?,?)"
                    : "UPDATE " + table + " SET reminder_day=? WHERE uuid=?";
            try (var update = connection.prepareStatement(sql)) {
                update.setString(1, day.toString());
                update.setString(2, uuid.toString());
                update.executeUpdate();
            }
            return true;
        });
    }
}
