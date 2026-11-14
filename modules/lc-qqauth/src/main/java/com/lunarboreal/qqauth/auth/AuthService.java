package com.lunarboreal.qqauth.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;

/** Platform-independent auth domain. JDBC and BCrypt methods must be called on a bounded worker.
 * Cache/session queries are safe from event threads. Never serialize PreparedPassword or repository internals.
 */
public final class AuthService {
    @FunctionalInterface public interface IdentityMigration {
        boolean migrate(java.sql.Connection connection, String name, UUID previous, UUID next) throws SQLException;
    }
    public enum Result { SUCCESS, NOT_REGISTERED, ALREADY_REGISTERED, IDENTITY_MISMATCH, LOCKED,
        INVALID_PASSWORD, WRONG_PASSWORD, THROTTLED, IP_LIMIT, STALE_GENERATION, NOT_AUTHENTICATED }
    public record AccountView(UUID uuid, String name, String generation, boolean locked, long createdAt) { }
    /** Opaque BCrypt-only recovery value: no plaintext or public hash accessor. */
    public static final class PreparedPassword {
        private final String hash;
        private PreparedPassword(String hash) { this.hash = hash; }
        @Override public String toString() { return "PreparedPassword[redacted]"; }
    }
    private final SqlAccountRepository repository;
    private final AuthPolicy policy;
    private final boolean nameBasedIdentity;
    private final Clock clock;
    private final Map<UUID, Session> online = new HashMap<>();
    private final Map<UUID, Session> remembered = new HashMap<>();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private record Session(String key, String generation, String ip, long expires) { }
    private record Attempt(int count, long expires) { }

    public AuthService(SqlAccountRepository repository, AuthPolicy policy) { this(repository, policy, false, Clock.systemUTC()); }
    public AuthService(SqlAccountRepository repository, AuthPolicy policy, boolean nameBasedIdentity) {
        this(repository, policy, nameBasedIdentity, Clock.systemUTC());
    }
    public AuthService(SqlAccountRepository repository, AuthPolicy policy, Clock clock) {
        this(repository, policy, false, clock);
    }
    public AuthService(SqlAccountRepository repository, AuthPolicy policy, boolean nameBasedIdentity, Clock clock) {
        this.repository = Objects.requireNonNull(repository); this.policy = Objects.requireNonNull(policy);
        this.nameBasedIdentity = nameBasedIdentity;
        this.clock = Objects.requireNonNull(clock);
    }
    public static String nameKey(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_]{3,16}")) throw new IllegalArgumentException("Invalid player name");
        return name.toLowerCase(Locale.ROOT);
    }
    private static String ip(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 64) throw new IllegalArgumentException("Invalid IP address");
        return ip;
    }
    public Optional<AccountView> account(String name) { return repository.account(name); }
    /** Cache-only UUID lookup, safe on event threads. */
    public Optional<AccountView> account(UUID uuid) {
        Objects.requireNonNull(uuid);
        return repository.accounts().stream().filter(a -> a.uuid().equals(uuid)).findFirst();
    }
    /** Call only after the platform verifies a stable UUID/name association, on an async worker.
     * A real rename rotates the generation, invalidating sessions and old recovery snapshots.
     */
    public Result renameByUuid(UUID uuid, String name) throws SQLException {
        return repository.renameByUuid(uuid, name);
    }
    public List<AccountView> accounts() { return repository.accounts(); }
    public boolean validPassword(String password) {
        if (password == null) return false;
        int length = password.codePointCount(0, password.length());
        return length >= policy.minPasswordLength() && length <= policy.maxPasswordLength()
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
    public PreparedPassword preparePassword(String password) {
        if (!validPassword(password)) throw new IllegalArgumentException("Password does not satisfy policy");
        char[] chars = password.toCharArray();
        try { return new PreparedPassword(BCrypt.withDefaults().hashToString(policy.bcryptCost(), chars)); }
        finally { Arrays.fill(chars, '\0'); }
    }
    private boolean verify(String password, String hash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        char[] chars = password.toCharArray();
        try { return BCrypt.verifyer().verify(chars, hash).verified; }
        catch (IllegalArgumentException e) { return false; }
        finally { Arrays.fill(chars, '\0'); }
    }
    /** Admission consumes both account and IP budgets even for successful login; no success clears IP limits.
     * At capacity fail closed rather than evicting live attacker buckets and reopening their budgets. */
    private synchronized boolean admit(String key, String ip) {
        long now = clock.millis();
        attempts.entrySet().removeIf(e -> e.getValue().expires <= now);
        String accountKey = "a:" + key, ipKey = "i:" + ip;
        Attempt a = attempts.get(accountKey), b = attempts.get(ipKey);
        if ((a != null && a.count >= policy.maxAttempts()) || (b != null && b.count >= policy.maxAttempts())) return false;
        int needed = (a == null ? 1 : 0) + (b == null ? 1 : 0);
        if (attempts.size() + needed > policy.maxAttemptEntries()) return false;
        attempts.put(accountKey, next(a, now)); attempts.put(ipKey, next(b, now));
        return true;
    }
    private Attempt next(Attempt old, long now) {
        return old == null ? new Attempt(1, now + policy.attemptWindow().toMillis()) : new Attempt(old.count + 1, old.expires);
    }
    public Result register(UUID uuid, String name, String ip, String password) throws SQLException {
        Objects.requireNonNull(uuid); String key = nameKey(name); ip = ip(ip);
        if (!admit(key, ip)) return Result.THROTTLED;
        if (!validPassword(password)) return Result.INVALID_PASSWORD;
        PreparedPassword prepared = preparePassword(password);
        Result result;
        SqlAccountRepository.Row registered;
        synchronized (repository) {
            result = repository.register(uuid, name, ip, prepared.hash, policy.maxAccountsPerIp(), clock.millis());
            registered = result == Result.SUCCESS ? repository.row(key) : null;
        }
        if (registered != null && !authenticate(registered, ip)) return Result.STALE_GENERATION;
        return result;
    }
    public Result login(UUID uuid, String name, String ip, String password) throws SQLException {
        return login(uuid, name, ip, password, (connection, playerName, previous, next) -> true);
    }
    public Result login(UUID uuid, String name, String ip, String password, IdentityMigration migration) throws SQLException {
        Objects.requireNonNull(uuid); String key = nameKey(name); ip = ip(ip);
        if (!admit(key, ip)) return Result.THROTTLED;
        SqlAccountRepository.Row row = repository.row(key);
        Result check = loginIdentity(row, uuid);
        if (check != Result.SUCCESS) return check;
        if (!verify(password, row.hash)) return Result.WRONG_PASSWORD;
        if (!row.uuid.equals(uuid)) {
            UUID previous = row.uuid;
            synchronized (repository) {
                Result migrated = repository.migrateUuid(key, previous, uuid, row.generation, migration);
                if (migrated != Result.SUCCESS) return migrated;
                row = repository.row(key);
            }
            logout(previous);
        }
        // Recheck under the session lock; concurrent reset invalidates this generation.
        return authenticate(row, ip) ? Result.SUCCESS : Result.STALE_GENERATION;
    }
    private Result loginIdentity(SqlAccountRepository.Row row, UUID uuid) {
        if (row == null) return Result.NOT_REGISTERED;
        if (!row.uuid.equals(uuid) && !nameBasedIdentity) return Result.IDENTITY_MISMATCH;
        return row.locked ? Result.LOCKED : Result.SUCCESS;
    }
    private Result identity(SqlAccountRepository.Row row, UUID uuid) {
        if (row == null) return Result.NOT_REGISTERED;
        if (!row.uuid.equals(uuid)) return Result.IDENTITY_MISMATCH;
        return row.locked ? Result.LOCKED : Result.SUCCESS;
    }
    private synchronized boolean authenticate(SqlAccountRepository.Row row, String ip) {
        SqlAccountRepository.Row current = repository.row(row.key);
        if (current == null || current.locked || !current.generation.equals(row.generation)) return false;
        Session session = new Session(row.key, row.generation, ip, clock.millis() + policy.sessionTtl().toMillis());
        if (!online.containsKey(row.uuid) && online.size() >= policy.maxSessions()) return false;
        online.put(row.uuid, session);
        remembered.entrySet().removeIf(e -> e.getValue().expires <= clock.millis());
        if (policy.sessionsEnabled() && (remembered.containsKey(row.uuid) || remembered.size() < policy.maxSessions())) remembered.put(row.uuid, session);
        return true;
    }
    public synchronized boolean isAuthenticated(UUID uuid) {
        Session s = online.get(uuid);
        if (s == null) return false;
        SqlAccountRepository.Row row = repository.row(s.key);
        boolean valid = row != null && row.uuid.equals(uuid) && !row.locked && row.generation.equals(s.generation);
        if (!valid) { online.remove(uuid); remembered.remove(uuid); }
        return valid;
    }
    public synchronized Result resumeSession(UUID uuid, String name, String ip) {
        String key = nameKey(name); ip = ip(ip);
        if (!policy.sessionsEnabled()) return Result.NOT_AUTHENTICATED;
        Session s = remembered.get(uuid);
        if (s == null || s.expires <= clock.millis() || !s.key.equals(key) || !s.ip.equals(ip)) return Result.NOT_AUTHENTICATED;
        SqlAccountRepository.Row row = repository.row(key);
        Result check = identity(row, uuid);
        if (check != Result.SUCCESS) return check;
        if (!s.generation.equals(row.generation)) return Result.STALE_GENERATION;
        // Restore without extending the original fixed expiry.
        if (!online.containsKey(uuid) && online.size() >= policy.maxSessions()) return Result.THROTTLED;
        online.put(uuid, s);
        return Result.SUCCESS;
    }
    public synchronized void logout(UUID uuid) { online.remove(uuid); remembered.remove(uuid); }
    public synchronized void disconnect(UUID uuid) { online.remove(uuid); }
    public Result changePassword(UUID uuid, String name, String ip, String oldPassword, String newPassword) throws SQLException {
        String key = nameKey(name); ip = ip(ip);
        if (!admit(key, ip)) return Result.THROTTLED;
        SqlAccountRepository.Row row = repository.row(key);
        Result check = identity(row, uuid);
        if (check != Result.SUCCESS) return check;
        if (!isAuthenticated(uuid)) return Result.NOT_AUTHENTICATED;
        if (!verify(oldPassword, row.hash)) return Result.WRONG_PASSWORD;
        if (!validPassword(newPassword)) return Result.INVALID_PASSWORD;
        Result result = repository.replacePassword(key, row.generation, preparePassword(newPassword).hash);
        if (result == Result.SUCCESS) logout(uuid);
        return result;
    }
    public Result unregister(UUID uuid, String name, String ip, String password) throws SQLException {
        String key = nameKey(name); ip = ip(ip);
        if (!admit(key, ip)) return Result.THROTTLED;
        SqlAccountRepository.Row row = repository.row(key);
        Result check = identity(row, uuid);
        if (check != Result.SUCCESS) return check;
        if (!isAuthenticated(uuid)) return Result.NOT_AUTHENTICATED;
        if (!verify(password, row.hash)) return Result.WRONG_PASSWORD;
        Result result = repository.delete(key, row.generation);
        if (result == Result.SUCCESS) logout(uuid);
        return result;
    }
    public Result setPassword(String name, String expectedGeneration, PreparedPassword prepared) throws SQLException {
        Objects.requireNonNull(expectedGeneration); Objects.requireNonNull(prepared);
        return repository.replacePassword(nameKey(name), expectedGeneration, prepared.hash);
    }
    public Result adminReset(String name, String password) throws SQLException {
        if (!validPassword(password)) return Result.INVALID_PASSWORD;
        SqlAccountRepository.Row row = repository.row(nameKey(name));
        if (row == null) return Result.NOT_REGISTERED;
        return setPassword(name, row.generation, preparePassword(password));
    }
    public Result setLocked(String name, boolean locked) throws SQLException { return repository.setLocked(nameKey(name), locked); }
    public Result deleteAccount(String name) throws SQLException {
        SqlAccountRepository.Row row = repository.row(nameKey(name));
        return row == null ? Result.NOT_REGISTERED : repository.delete(row.key, row.generation);
    }
}
