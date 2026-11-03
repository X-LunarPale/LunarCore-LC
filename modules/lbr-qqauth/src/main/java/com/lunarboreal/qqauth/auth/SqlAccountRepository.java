package com.lunarboreal.qqauth.auth;

import com.lunarboreal.api.database.Database;
import java.sql.*;
import java.util.*;

/** JDBC storage for a single LunarCore server. Initialize on a worker before enabling authentication.
 * All writes take a database gate lock, including registration quota checks, and publish cache only
 * after commit. Sharing this table between live servers requires external cache invalidation.
 * Password material is deliberately package-private and absent from public account views.
 */
public final class SqlAccountRepository {
    private final Database db;
    private final String table, gate;
    private volatile Map<String, Row> cache = Map.of();
    private volatile boolean ready;

    public SqlAccountRepository(Database db) {
        this.db = Objects.requireNonNull(db);
        table = db.table("qqauth_accounts");
        gate = db.table("qqauth_auth_gate");
        if (!table.matches("[a-zA-Z0-9_]+") || !gate.matches("[a-zA-Z0-9_]+"))
            throw new IllegalArgumentException("Invalid database table prefix");
    }

    public synchronized void initialize() throws SQLException {
        Map<String, Row> loaded = db.transaction(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (name_key VARCHAR(16) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL UNIQUE, display_name VARCHAR(16) NOT NULL, password_hash VARCHAR(60) NOT NULL, registration_ip VARCHAR(64) NOT NULL, generation VARCHAR(36) NOT NULL, locked INTEGER NOT NULL, created_at BIGINT NOT NULL)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS " + gate + " (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL)");
            }
            try (PreparedStatement p = c.prepareStatement("SELECT id FROM " + gate + " WHERE id=1"); ResultSet r = p.executeQuery()) {
                if (!r.next()) execute(c, "INSERT INTO " + gate + " (id,revision) VALUES (1,0)");
            }
            lock(c);
            return load(c);
        });
        cache = loaded;
        ready = true;
    }

    public boolean isReady() { return ready; }
    public List<AuthService.AccountView> accounts() {
        if (!ready) throw new IllegalStateException("Authentication storage not initialized");
        return cache.values().stream().map(Row::view).sorted(Comparator.comparing(AuthService.AccountView::name)).toList();
    }
    public Optional<AuthService.AccountView> account(String name) {
        return Optional.ofNullable(row(AuthService.nameKey(name))).map(Row::view);
    }
    Row row(String key) {
        if (!ready) throw new IllegalStateException("Authentication storage not initialized");
        return cache.get(key);
    }
    private void lock(Connection c) throws SQLException {
        execute(c, "UPDATE " + gate + " SET revision=revision+1 WHERE id=1");
    }
    private Map<String, Row> load(Connection c) throws SQLException {
        Map<String, Row> rows = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM " + table)) {
            while (r.next()) {
                Row row = new Row(r.getString("name_key"), UUID.fromString(r.getString("player_uuid")),
                        r.getString("display_name"), r.getString("password_hash"), r.getString("registration_ip"),
                        r.getString("generation"), r.getInt("locked") != 0, r.getLong("created_at"));
                rows.put(row.key, row);
            }
        }
        return Map.copyOf(rows);
    }
    private synchronized AuthService.Result mutate(Work work) throws SQLException {
        if (!ready) throw new IllegalStateException("Authentication storage not initialized");
        Mutation m = db.transaction(c -> {
            lock(c);
            Map<String, Row> current = load(c);
            AuthService.Result result = work.run(c, current);
            return new Mutation(result, load(c));
        });
        cache = m.rows; // transaction() has committed successfully
        return m.result;
    }
    AuthService.Result register(UUID uuid, String name, String ip, String hash, int maxIp, long now) throws SQLException {
        String key = AuthService.nameKey(name);
        return mutate((c, rows) -> {
            if (rows.containsKey(key)) return AuthService.Result.ALREADY_REGISTERED;
            if (rows.values().stream().anyMatch(r -> r.uuid.equals(uuid))) return AuthService.Result.IDENTITY_MISMATCH;
            if (rows.values().stream().filter(r -> r.ip.equals(ip)).count() >= maxIp) return AuthService.Result.IP_LIMIT;
            execute(c, "INSERT INTO " + table + " (name_key,player_uuid,display_name,password_hash,registration_ip,generation,locked,created_at) VALUES (?,?,?,?,?,?,0,?)",
                    key, uuid.toString(), name, hash, ip, UUID.randomUUID().toString(), now);
            return AuthService.Result.SUCCESS;
        });
    }
    /** Rename only an existing UUID; target ownership and cache publication share the mutation gate. */
    AuthService.Result renameByUuid(UUID uuid, String name) throws SQLException {
        Objects.requireNonNull(uuid);
        String key = AuthService.nameKey(name);
        return mutate((c, rows) -> {
            Row current = rows.values().stream().filter(r -> r.uuid.equals(uuid)).findFirst().orElse(null);
            if (current == null) return AuthService.Result.NOT_REGISTERED;
            Row target = rows.get(key);
            if (target != null && !target.uuid.equals(uuid)) return AuthService.Result.IDENTITY_MISMATCH;
            if (current.name.equals(name)) return AuthService.Result.SUCCESS;
            return execute(c, "UPDATE " + table + " SET name_key=?,display_name=?,generation=? WHERE player_uuid=? AND generation=?",
                    key, name, UUID.randomUUID().toString(), uuid.toString(), current.generation) == 1
                    ? AuthService.Result.SUCCESS : AuthService.Result.STALE_GENERATION;
        });
    }
    AuthService.Result replacePassword(String key, String generation, String hash) throws SQLException {
        return mutate((c, rows) -> {
            Row row = rows.get(key);
            if (row == null) return AuthService.Result.NOT_REGISTERED;
            if (!row.generation.equals(generation)) return AuthService.Result.STALE_GENERATION;
            return execute(c, "UPDATE " + table + " SET password_hash=?,generation=? WHERE name_key=? AND generation=?",
                    hash, UUID.randomUUID().toString(), key, generation) == 1 ? AuthService.Result.SUCCESS : AuthService.Result.STALE_GENERATION;
        });
    }
    AuthService.Result setLocked(String key, boolean locked) throws SQLException {
        return mutate((c, rows) -> {
            if (!rows.containsKey(key)) return AuthService.Result.NOT_REGISTERED;
            execute(c, "UPDATE " + table + " SET locked=?,generation=? WHERE name_key=?", locked ? 1 : 0, UUID.randomUUID().toString(), key);
            return AuthService.Result.SUCCESS;
        });
    }
    AuthService.Result delete(String key, String generation) throws SQLException {
        return mutate((c, rows) -> {
            Row row = rows.get(key);
            if (row == null) return AuthService.Result.NOT_REGISTERED;
            if (!row.generation.equals(generation)) return AuthService.Result.STALE_GENERATION;
            return execute(c, "DELETE FROM " + table + " WHERE name_key=? AND generation=?", key, generation) == 1
                    ? AuthService.Result.SUCCESS : AuthService.Result.STALE_GENERATION;
        });
    }
    private static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            return p.executeUpdate();
        }
    }
    private interface Work { AuthService.Result run(Connection c, Map<String, Row> rows) throws SQLException; }
    private record Mutation(AuthService.Result result, Map<String, Row> rows) { }
    static final class Row {
        final String key, name, hash, ip, generation;
        final UUID uuid;
        final boolean locked;
        final long created;
        Row(String key, UUID uuid, String name, String hash, String ip, String generation, boolean locked, long created) {
            this.key = key; this.uuid = uuid; this.name = name; this.hash = hash; this.ip = ip;
            this.generation = generation; this.locked = locked; this.created = created;
        }
        AuthService.AccountView view() { return new AuthService.AccountView(uuid, name, generation, locked, created); }
        @Override public String toString() { return "Account[redacted]"; }
    }
}
