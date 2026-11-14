package com.lunarboreal.qqauth.binding;

import com.lunarboreal.api.database.Database;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SQL-backed binding persistence. Mutations and code redemption run transactionally. */
public final class BindingRepository {
    private final Database database;
    private final String table;

    public BindingRepository(Database database) {
        this.database = java.util.Objects.requireNonNull(database, "database");
        this.table = database.table("qqauth_bindings");
    }

    public void initialize() throws SQLException {
        String idType = database.isMySQL() ? "VARCHAR(36)" : "TEXT";
        String textType = database.isMySQL() ? "VARCHAR(255)" : "TEXT";
        try (var c = database.connection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (uuid " + idType + " PRIMARY KEY, player_name " + textType + " NOT NULL, name_key VARCHAR(16) NOT NULL UNIQUE, qq " + textType + " NOT NULL, qq_key VARCHAR(2040) NOT NULL, group_id " + textType + " NOT NULL, bound_at BIGINT NOT NULL)");
        }
        // A shared mutex row serializes quota checks on both SQLite and MySQL.
        database.update("CREATE TABLE IF NOT EXISTS " + table + "_lock (id INTEGER PRIMARY KEY, revision INTEGER NOT NULL)");
        database.update(database.upsert(table + "_lock", List.of("id"), List.of("id", "revision")), 1, 0);
    }

    public Optional<Binding> find(UUID uuid) throws SQLException {
        return database.queryOne("SELECT uuid,player_name,qq,group_id,bound_at FROM " + table + " WHERE uuid=?", BindingRepository::map, uuid.toString());
    }
    public Optional<Binding> findByName(String name) throws SQLException {
        return database.queryOne("SELECT uuid,player_name,qq,group_id,bound_at FROM " + table + " WHERE LOWER(name_key)=LOWER(?)", BindingRepository::map, name);
    }
    public Optional<Binding> findByQq(String qq) throws SQLException {
        return database.queryOne("SELECT uuid,player_name,qq,group_id,bound_at FROM " + table + " WHERE qq_key=? LIMIT 1", BindingRepository::map, qqKey(qq));
    }
    public List<Binding> all() throws SQLException {
        return database.query("SELECT uuid,player_name,qq,group_id,bound_at FROM " + table, BindingRepository::map);
    }
    public long countForQq(String qq) throws SQLException {
        return database.queryOne("SELECT COUNT(*) AS n FROM " + table + " WHERE qq_key=?", rs -> rs.getLong("n"), qqKey(qq)).orElse(0L);
    }
    public void updateName(UUID uuid, String name) throws SQLException {
        database.update("UPDATE " + table + " SET player_name=?,name_key=? WHERE uuid=?", name, nameKey(name), uuid.toString());
    }
    boolean migrateUuid(java.sql.Connection c, String name, UUID oldUuid, UUID newUuid) throws SQLException {
        lock(c);
        try (var ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE uuid=?")) {
            ps.setString(1, newUuid.toString());
            try (var rs = ps.executeQuery()) { if (rs.next()) return false; }
        }
        try (var ps = c.prepareStatement("SELECT uuid FROM " + table + " WHERE LOWER(name_key)=LOWER(?)")) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return true;
                if (!oldUuid.toString().equals(rs.getString(1))) return false;
            }
        }
        try (var ps = c.prepareStatement("UPDATE " + table + " SET uuid=?,player_name=? WHERE uuid=? AND LOWER(name_key)=LOWER(?)")) {
            ps.setString(1, newUuid.toString()); ps.setString(2, name); ps.setString(3, oldUuid.toString()); ps.setString(4, name);
            return ps.executeUpdate() == 1;
        }
    }

    /** Insert only if UUID and case-insensitive name are free; quota is checked in the same transaction. */
    public boolean bind(Binding binding, int quota) throws SQLException {
        return database.transaction(c -> {
            lock(c);
            if (exists(c, "uuid", binding.uuid().toString()) || exists(c, "LOWER(name_key)", binding.playerName().toLowerCase(java.util.Locale.ROOT))) return false;
            try (var ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE qq_key=?")) {
                ps.setString(1, qqKey(binding.qq()));
                try (var rs = ps.executeQuery()) { if (rs.next() && rs.getLong(1) >= quota) return false; }
            }
            try (var ps = c.prepareStatement("INSERT INTO " + table + " (uuid,player_name,name_key,qq,qq_key,group_id,bound_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, binding.uuid().toString()); ps.setString(2, binding.playerName()); ps.setString(3, nameKey(binding.playerName()));
                ps.setString(4, binding.qq()); ps.setString(5, qqKey(binding.qq())); ps.setString(6, binding.group()); ps.setLong(7, binding.boundAt());
                ps.executeUpdate(); return true;
            }
        });
    }
    public boolean unbind(UUID uuid) throws SQLException { return database.update("DELETE FROM " + table + " WHERE uuid=?", uuid.toString()) > 0; }
    public boolean unbind(Binding expected) throws SQLException {
        return database.transaction(c -> {
            lock(c);
            return database.update("DELETE FROM " + table + " WHERE uuid=? AND qq_key=? AND bound_at=?", expected.uuid().toString(), qqKey(expected.qq()), expected.boundAt()) > 0;
        });
    }
    public boolean unbindByName(String name) throws SQLException { return database.update("DELETE FROM " + table + " WHERE LOWER(name_key)=LOWER(?)", name) > 0; }

    /** Import is all-or-nothing, including case-insensitive player-name and configured QQ quotas. */
    public void importBindings(Collection<Binding> records, BindingSettings settings) throws SQLException {
        database.transaction(c -> {
            lock(c);
            java.util.HashSet<UUID> uuids = new java.util.HashSet<>();
            java.util.HashSet<String> names = new java.util.HashSet<>();
            java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
            try (var ps = c.prepareStatement("SELECT uuid,player_name,qq FROM " + table); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    uuids.add(UUID.fromString(rs.getString("uuid"))); names.add(nameKey(rs.getString("player_name")));
                    counts.merge(rs.getString("qq"), 1, Integer::sum);
                }
            }
            try (var ps = c.prepareStatement("INSERT INTO " + table + " (uuid,player_name,name_key,qq,qq_key,group_id,bound_at) VALUES (?,?,?,?,?,?,?)")) {
                for (Binding b : records) {
                    String key = b.playerName().toLowerCase(java.util.Locale.ROOT);
                    if (!uuids.add(b.uuid()) || !names.add(key)) throw new SQLException("duplicate UUID or player name in import");
                    int count = counts.merge(b.qq(), 1, Integer::sum);
                    if (count > settings.quotaFor(b.qq())) throw new SQLException("QQ quota exceeded in import");
                    ps.setString(1, b.uuid().toString()); ps.setString(2, b.playerName()); ps.setString(3, nameKey(b.playerName()));
                    ps.setString(4, b.qq()); ps.setString(5, qqKey(b.qq())); ps.setString(6, b.group()); ps.setLong(7, b.boundAt()); ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    private void lock(java.sql.Connection c) throws SQLException {
        try (var ps = c.prepareStatement("UPDATE " + table + "_lock SET revision=revision+1 WHERE id=1")) { ps.executeUpdate(); }
    }
    private static String nameKey(String value) { return value.toLowerCase(java.util.Locale.ROOT); }
    private static String qqKey(String value) { return java.util.HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    private boolean exists(java.sql.Connection c, String field, String value) throws SQLException {
        try (var ps = c.prepareStatement("SELECT 1 FROM " + table + " WHERE " + field + "=?")) {
            ps.setString(1, value); try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }
    private static Binding map(java.sql.ResultSet rs) throws SQLException {
        return new Binding(UUID.fromString(rs.getString("uuid")), rs.getString("player_name"), rs.getString("qq"), rs.getString("group_id"), rs.getLong("bound_at"));
    }
}
