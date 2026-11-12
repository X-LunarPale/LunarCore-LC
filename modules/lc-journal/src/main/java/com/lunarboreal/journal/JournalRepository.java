package com.lunarboreal.journal;

import com.lunarboreal.api.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JournalRepository {

    @FunctionalInterface
    interface Connections { Connection open() throws SQLException; }

    public record Saved(JournalEntry entry, boolean inserted) {}
    public record Stats(long total, long players, long biomes, long advancements, long fishing) {}
    public record Page(long total, List<JournalEntry> entries) {}

    private final Connections connections;
    private final String table;

    public JournalRepository(Database database) {
        this(database::connection, database.table("journal_entries"));
    }

    JournalRepository(Connections connections, String table) {
        if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid table name");
        this.connections = connections;
        this.table = table;
    }

    public void init() throws SQLException {
        try (Connection connection = connections.open(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "uuid VARCHAR(36) NOT NULL, player_name VARCHAR(64) NOT NULL, "
                    + "category VARCHAR(24) NOT NULL, entry_key VARCHAR(255) NOT NULL, "
                    + "label VARCHAR(255) NOT NULL, world VARCHAR(128) NOT NULL, first_seen BIGINT NOT NULL, "
                    + "PRIMARY KEY (uuid, category, entry_key))");
        }
    }

    /** The unique key owns the original timestamp; a rediscovery never updates it. */
    public Saved save(JournalEntry entry) throws SQLException {
        try (Connection connection = connections.open()) {
            JournalEntry existing = find(connection, entry);
            if (existing != null) return new Saved(existing, false);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + table
                    + " (uuid, player_name, category, entry_key, label, world, first_seen) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setQueryTimeout(3);
                statement.setString(1, entry.uuid().toString());
                statement.setString(2, entry.playerName());
                statement.setString(3, entry.category().id());
                statement.setString(4, entry.key());
                statement.setString(5, entry.label());
                statement.setString(6, entry.world());
                statement.setLong(7, entry.firstSeen());
                statement.executeUpdate();
                return new Saved(entry, true);
            } catch (SQLException failure) {
                // Another writer can win the insert after our read.
                if (failure.getErrorCode() == 19 || (failure.getSQLState() != null
                        && failure.getSQLState().startsWith("23"))) {
                    existing = find(connection, entry);
                    if (existing != null) return new Saved(existing, false);
                }
                throw failure;
            }
        }
    }

    private JournalEntry find(Connection connection, JournalEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table
                + " WHERE uuid = ? AND category = ? AND entry_key = ?")) {
            statement.setQueryTimeout(3);
            statement.setString(1, entry.uuid().toString());
            statement.setString(2, entry.category().id());
            statement.setString(3, entry.key());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    public Page page(UUID uuid, JournalEntry.Category category, String query, long offset, int size) throws SQLException {
        if (offset < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page");
        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (uuid != null) { where.append(" AND uuid = ?"); parameters.add(uuid.toString()); }
        if (category != null) { where.append(" AND category = ?"); parameters.add(category.id()); }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" AND (player_name LIKE ? ESCAPE '!' OR uuid LIKE ? ESCAPE '!'"
                    + " OR entry_key LIKE ? ESCAPE '!' OR label LIKE ? ESCAPE '!' OR world LIKE ? ESCAPE '!')");
            for (int i = 0; i < 5; i++) parameters.add(pattern);
        }
        try (Connection connection = connections.open()) {
            long total;
            try (PreparedStatement statement = prepare(connection, "SELECT COUNT(*) FROM " + table + where, parameters);
                 ResultSet result = statement.executeQuery()) {
                total = result.next() ? result.getLong(1) : 0;
            }
            List<Object> paged = new ArrayList<>(parameters);
            paged.add(size);
            paged.add(offset);
            List<JournalEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = prepare(connection, "SELECT * FROM " + table + where
                    + " ORDER BY first_seen DESC, uuid ASC, category ASC, entry_key ASC LIMIT ? OFFSET ?", paged);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) entries.add(read(result));
            }
            return new Page(total, List.copyOf(entries));
        }
    }

    public Stats stats(UUID uuid) throws SQLException {
        String where = uuid == null ? "" : " WHERE uuid = ?";
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*), COUNT(DISTINCT uuid), "
                     + "SUM(CASE WHEN category = 'biomes' THEN 1 ELSE 0 END), "
                     + "SUM(CASE WHEN category = 'advancements' THEN 1 ELSE 0 END), "
                     + "SUM(CASE WHEN category = 'fishing' THEN 1 ELSE 0 END) FROM " + table + where)) {
            statement.setQueryTimeout(3);
            if (uuid != null) statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new Stats(result.getLong(1), result.getLong(2), result.getLong(3), result.getLong(4), result.getLong(5));
            }
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, List<Object> parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(3);
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            return statement;
        } catch (SQLException error) {
            statement.close();
            throw error;
        }
    }

    private static JournalEntry read(ResultSet result) throws SQLException {
        return new JournalEntry(UUID.fromString(result.getString("uuid")), result.getString("player_name"),
                JournalEntry.Category.parse(result.getString("category")), result.getString("entry_key"),
                result.getString("label"), result.getString("world"), result.getLong("first_seen"));
    }
}
