package com.lunarboreal.core.web;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.gson.reflect.TypeToken;
import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.web.HttpException;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Web 账号、角色、JWT、登录锁定与游戏账号绑定码。 */
final class AuthManager {

    /**
     * @param tokenVersion 签发 JWT 时写入 claim "tv"；改密/重置/登出时递增，使旧 token 全部失效（持久化，重启后仍有效）。
     */
    record UserRow(String username, String hash, String role, long created, long lastLogin, String lastIp,
                   String mcUuid, String mcName, int tokenVersion) {

        UserRow withHash(String h) { return new UserRow(username, h, role, created, lastLogin, lastIp, mcUuid, mcName, tokenVersion + 1); }

        UserRow withRole(String r) { return new UserRow(username, hash, r, created, lastLogin, lastIp, mcUuid, mcName, tokenVersion); }

        UserRow withLogin(long t, String ip) { return new UserRow(username, hash, role, created, t, ip, mcUuid, mcName, tokenVersion); }

        UserRow withMc(String uuid, String name) { return new UserRow(username, hash, role, created, lastLogin, lastIp, uuid, name, tokenVersion); }

        UserRow bumpToken() { return new UserRow(username, hash, role, created, lastLogin, lastIp, mcUuid, mcName, tokenVersion + 1); }
    }

    record RoleRow(String name, String display, Set<String> permissions, boolean builtin) {}

    record BindCode(UUID uuid, String name, long expiresAt) {}

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_\\-.]{2,32}$");
    static final int MIN_PASSWORD = 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PW_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Map<String, UserRow> users = new ConcurrentHashMap<>();
    private final Map<String, RoleRow> roles = new ConcurrentHashMap<>();
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final Map<String, BindCode> bindCodes = new ConcurrentHashMap<>();

    private Database db;
    private String usersTable;
    private String rolesTable;
    private Algorithm algorithm;
    private JWTVerifier verifier;
    private TotpManager totp;
    private int sessionHours = 24;
    private int maxAttempts = 5;
    private int lockMinutes = 10;

    /** @return 首次创建 admin 时生成的明文密码，否则 null。 */
    String init(Database db, WebConfig cfg, String secret) throws SQLException {
        this.db = db;
        this.usersTable = db.table("web_users");
        this.rolesTable = db.table("web_roles");
        this.sessionHours = cfg.sessionHours();
        this.maxAttempts = cfg.loginMaxAttempts();
        this.lockMinutes = cfg.loginLockMinutes();
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).withIssuer("LunarCore").build();
        this.totp = new TotpManager(db, secret);

        db.update("CREATE TABLE IF NOT EXISTS " + usersTable + " ("
                + "username VARCHAR(32) NOT NULL PRIMARY KEY,"
                + "password_hash VARCHAR(100) NOT NULL,"
                + "role VARCHAR(32) NOT NULL,"
                + "created BIGINT NOT NULL,"
                + "last_login BIGINT NOT NULL DEFAULT 0,"
                + "last_ip VARCHAR(64),"
                + "mc_uuid VARCHAR(36),"
                + "mc_name VARCHAR(32),"
                + "token_version INT NOT NULL DEFAULT 0)");
        try {
            db.update("ALTER TABLE " + usersTable + " ADD COLUMN token_version INT NOT NULL DEFAULT 0");
        } catch (SQLException ignored) {
            // 列已存在
        }
        db.update("CREATE TABLE IF NOT EXISTS " + rolesTable + " ("
                + "name VARCHAR(32) NOT NULL PRIMARY KEY,"
                + "display VARCHAR(64),"
                + "permissions TEXT,"
                + "builtin INT NOT NULL DEFAULT 0)");

        roles.clear();
        users.clear();
        for (RoleRow r : db.query("SELECT name, display, permissions, builtin FROM " + rolesTable, rs -> new RoleRow(
                rs.getString(1), rs.getString(2), parsePerms(rs.getString(3)), rs.getInt(4) != 0))) {
            roles.put(r.name.toLowerCase(Locale.ROOT), r);
        }
        ensureBuiltin("admin", "管理员", List.of("*"), true);
        // 注意：core.console.view 会看到服务器全部日志；core.players.manage 不含以玩家身份执行命令（需 core.console.execute）
        ensureBuiltin("moderator", "协管", List.of("core.dashboard", "core.players.view", "core.players.manage",
                "core.console.view", "core.audit.view", "core.modules.view", "core.plugins.view", "*.view", "ban.*"), false);
        ensureBuiltin("viewer", "观察者", List.of("core.dashboard", "*.view", "-core.console.view"), false);

        for (UserRow u : db.query("SELECT username, password_hash, role, created, last_login, last_ip, mc_uuid, mc_name, token_version FROM "
                + usersTable, rs -> new UserRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9)))) {
            users.put(u.username.toLowerCase(Locale.ROOT), u);
        }
        if (users.isEmpty()) {
            String pw = randomPassword();
            createUser("admin", pw, "admin");
            return pw;
        }
        return null;
    }

    private void ensureBuiltin(String name, String display, List<String> perms, boolean force) throws SQLException {
        RoleRow existing = roles.get(name);
        if (existing != null && !force) return;
        saveRole(new RoleRow(name, display, new LinkedHashSet<>(perms), true));
    }

    // ------------------------------------------------------------------ 角色

    Collection<RoleRow> roles() {
        List<RoleRow> list = new ArrayList<>(roles.values());
        list.sort(Comparator.comparing((RoleRow r) -> !r.builtin).thenComparing(RoleRow::name));
        return list;
    }

    RoleRow role(String name) {
        return name == null ? null : roles.get(name.toLowerCase(Locale.ROOT));
    }

    synchronized void saveRole(RoleRow row) throws SQLException {
        db.update(db.upsert(rolesTable, List.of("name"), List.of("name", "display", "permissions", "builtin")),
                row.name, row.display, Json.GSON.toJson(row.permissions), row.builtin ? 1 : 0);
        roles.put(row.name.toLowerCase(Locale.ROOT), row);
    }

    void deleteRole(String name) throws SQLException {
        db.update("DELETE FROM " + rolesTable + " WHERE name = ?", name);
        roles.remove(name.toLowerCase(Locale.ROOT));
    }

    boolean roleInUse(String name) {
        return users.values().stream().anyMatch(u -> u.role.equalsIgnoreCase(name));
    }

    private static Set<String> parsePerms(String json) {
        if (json == null || json.isBlank()) return new LinkedHashSet<>();
        try {
            List<String> list = Json.GSON.fromJson(json, new TypeToken<List<String>>() {}.getType());
            return list == null ? new LinkedHashSet<>() : new LinkedHashSet<>(list);
        } catch (RuntimeException e) {
            return new LinkedHashSet<>();
        }
    }

    // ------------------------------------------------------------------ 用户

    Collection<UserRow> users() {
        List<UserRow> list = new ArrayList<>(users.values());
        list.sort(Comparator.comparing(UserRow::username, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    UserRow user(String name) {
        return name == null ? null : users.get(name.toLowerCase(Locale.ROOT));
    }

    UserRow userByMc(UUID uuid) {
        String s = uuid.toString();
        return users.values().stream().filter(u -> s.equalsIgnoreCase(u.mcUuid)).findFirst().orElse(null);
    }

    long countAdmins() {
        return users.values().stream().filter(u -> u.role.equalsIgnoreCase("admin")).count();
    }

    synchronized UserRow createUser(String username, String password, String role) throws SQLException {
        if (!NAME.matcher(username).matches()) throw new IllegalArgumentException("用户名只能包含字母、数字、_-.，长度 2-32");
        if (user(username) != null) throw new IllegalArgumentException("用户已存在：" + username);
        if (role(role) == null) throw new IllegalArgumentException("角色不存在：" + role);
        checkPassword(password);
        // A recreated username must not accept JWTs issued to the deleted account.
        UserRow row = new UserRow(username, hash(password), role(role).name, System.currentTimeMillis(), 0, null, null, null,
                RANDOM.nextInt(1, Integer.MAX_VALUE));
        saveUser(row);
        return row;
    }

    private synchronized void saveUser(UserRow u) throws SQLException {
        db.update(db.upsert(usersTable, List.of("username"), List.of("username", "password_hash", "role", "created",
                        "last_login", "last_ip", "mc_uuid", "mc_name", "token_version")),
                u.username, u.hash, u.role, u.created, u.lastLogin, u.lastIp, u.mcUuid, u.mcName, u.tokenVersion);
        users.put(u.username.toLowerCase(Locale.ROOT), u);
    }

    synchronized void deleteUser(String username) throws SQLException {
        UserRow u = user(username);
        if (u == null) throw new IllegalArgumentException("用户不存在：" + username);
        if (u.role.equalsIgnoreCase("admin") && countAdmins() <= 1) {
            throw new IllegalArgumentException("至少需要保留一个管理员");
        }
        db.update("DELETE FROM " + usersTable + " WHERE username = ?", u.username);
        totp.remove(u.username);
        users.remove(username.toLowerCase(Locale.ROOT));
    }

    /** Compare the verified snapshot under the same lock as account changes. */
    synchronized UserRow changePassword(UserRow verified, int sessionVersion, String password) throws SQLException {
        UserRow current = user(verified.username);
        if (current == null || current.tokenVersion != sessionVersion || !current.hash.equals(verified.hash)) {
            throw new IllegalArgumentException("账号已变更，请重新登录后再修改密码");
        }
        return setPassword(current.username, password);
    }

    synchronized UserRow setPassword(String username, String password) throws SQLException {
        UserRow u = user(username);
        if (u == null) throw new IllegalArgumentException("用户不存在：" + username);
        checkPassword(password);
        UserRow n = u.withHash(hash(password));
        saveUser(n);
        return n;
    }

    synchronized UserRow setRole(String username, String role) throws SQLException {
        UserRow u = user(username);
        if (u == null) throw new IllegalArgumentException("用户不存在：" + username);
        RoleRow r = role(role);
        if (r == null) throw new IllegalArgumentException("角色不存在：" + role);
        if (u.role.equalsIgnoreCase("admin") && !r.name.equals("admin") && countAdmins() <= 1) {
            throw new IllegalArgumentException("至少需要保留一个管理员");
        }
        UserRow n = u.withRole(r.name);
        saveUser(n);
        return n;
    }

    /** 使该用户已签发的全部 token 失效（登出所有设备）。 */
    synchronized void invalidateTokens(String username) throws SQLException {
        UserRow u = user(username);
        if (u != null) saveUser(u.bumpToken());
    }

    synchronized UserRow bindMc(String username, UUID uuid, String name) throws SQLException {
        UserRow u = user(username);
        if (u == null) throw new IllegalArgumentException("用户不存在");
        if (uuid != null) {
            UserRow other = userByMc(uuid);
            if (other != null && !other.username.equalsIgnoreCase(username)) {
                throw new IllegalArgumentException("该游戏账号已绑定到 " + other.username);
            }
        }
        UserRow n = u.withMc(uuid == null ? null : uuid.toString(), name);
        saveUser(n);
        return n;
    }

    /** 重置/创建 admin，返回新密码（旧 token 全部失效）。 */
    synchronized String resetAdmin() throws SQLException {
        String pw = randomPassword();
        UserRow u = user("admin");
        if (u == null) {
            createUser("admin", pw, "admin");
        } else {
            saveUser(u.withHash(hash(pw)).withRole("admin"));
            totp.remove("admin");
        }
        return pw;
    }

    static void checkPassword(String pw) {
        if (pw == null || pw.length() < MIN_PASSWORD) throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD + " 位");
        if (pw.length() > 72) throw new IllegalArgumentException("密码不能超过 72 位");
    }

    private static String hash(String pw) {
        return BCrypt.withDefaults().hashToString(11, pw.toCharArray());
    }

    static boolean verifyPassword(String pw, String hash) {
        if (pw == null || hash == null) return false;
        try {
            return BCrypt.verifyer().verify(pw.toCharArray(), hash).verified;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String randomPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(PW_CHARS.charAt(RANDOM.nextInt(PW_CHARS.length())));
        return sb.toString();
    }

    static String randomSecret() {
        byte[] b = new byte[48];
        RANDOM.nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    Set<String> permissionsOf(String role) {
        RoleRow r = role(role);
        return r == null ? Set.of() : Set.copyOf(r.permissions);
    }

    // ------------------------------------------------------------------ 登录锁定

    /** @return 剩余锁定毫秒，0 表示未锁定。 */
    long lockRemaining(String key) {
        Long until = lockedUntil.get(key);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            lockedUntil.remove(key);
            return 0;
        }
        return left;
    }

    /** 记录失败，返回是否因此被锁定。key 通常为 IP，也可为 "user:<名>"。 */
    boolean recordFailure(String key) {
        if (failures.size() > 10_000 || lockedUntil.size() > 10_000) cleanup();
        int[] c = failures.computeIfAbsent(key, k -> new int[1]);
        synchronized (c) {
            c[0]++;
            if (c[0] >= maxAttempts) {
                c[0] = 0;
                lockedUntil.put(key, System.currentTimeMillis() + lockMinutes * 60_000L);
                return true;
            }
        }
        return false;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        lockedUntil.values().removeIf(t -> t < now);
        failures.clear();
    }

    void recordSuccess(String key) {
        failures.remove(key);
    }

    /** 用户名密码登录，成功返回用户（同时更新最后登录）。 */
    UserRow login(String username, String password, String ip) throws SQLException {
        UserRow u = checkCredentials(username, password);
        return u == null ? null : touch(u, ip);
    }

    UserRow checkCredentials(String username, String password) {
        UserRow u = user(username);
        // 即使用户不存在也执行一次哈希校验，避免时序探测
        boolean ok = verifyPassword(password, u == null ? "$2a$11$abcdefghijklmnopqrstuuWbFYBjCZJ0q1Vg8fYdOUj3ka5L1E6aW" : u.hash);
        return u == null || !ok ? null : u;
    }

    boolean totpEnabled(String username) { return totp.enabled(username); }
    TotpManager.Enrollment beginTotp(UserRow user) { return totp.begin(user); }
    synchronized List<String> enableTotp(UserRow user, int version, String code) throws SQLException {
        UserRow current = user(user.username);
        if (current == null || current.tokenVersion != version || !current.hash.equals(user.hash))
            throw HttpException.badRequest("账号已变更，请重新登录");
        List<String> recovery = totp.confirm(current, version, code);
        invalidateTokens(user.username);
        return recovery;
    }
    synchronized boolean disableTotp(UserRow verified, int version, String code) throws SQLException {
        UserRow current = user(verified.username);
        if (current == null || current.tokenVersion != version || !current.hash.equals(verified.hash)) return false;
        if (!totp.verify(current.username, code)) return false;
        totp.remove(current.username);
        invalidateTokens(current.username);
        return true;
    }
    synchronized void resetTotp(String username) throws SQLException {
        totp.remove(username);
        invalidateTokens(username);
    }
    String totpChallenge(UserRow user, String ip, String action, String how) {
        return totp.challenge(user, ip, action, how);
    }
    synchronized TotpManager.Login completeTotp(String challenge, String ip, String code) throws SQLException {
        return totp.complete(this, challenge, ip, code);
    }

    synchronized UserRow touch(UserRow u, String ip) throws SQLException {
        // Password verification runs outside this lock. Never upsert its stale snapshot:
        // a concurrent reset/logout must not be undone by a successful login.
        UserRow current = user(u.username);
        if (current == null || current.tokenVersion != u.tokenVersion || !current.hash.equals(u.hash)
                || !java.util.Objects.equals(current.mcUuid, u.mcUuid)) {
            throw new IllegalArgumentException("账号已变更，请重新登录");
        }
        UserRow n = current.withLogin(System.currentTimeMillis(), ip);
        saveUser(n);
        return n;
    }

    // ------------------------------------------------------------------ JWT

    String issue(UserRow u) {
        long now = System.currentTimeMillis();
        return JWT.create()
                .withIssuer("LunarCore")
                .withSubject(u.username)
                .withClaim("tv", u.tokenVersion)
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + sessionHours * 3_600_000L))
                .sign(algorithm);
    }

    /** 校验 token 并返回当前用户（角色以数据库实时值为准）；无效返回 null。 */
    WebUserImpl verify(String token) {
        if (token == null || token.isBlank() || verifier == null) return null;
        try {
            DecodedJWT jwt = verifier.verify(token);
            UserRow u = user(jwt.getSubject());
            if (u == null) return null;
            Claim tv = jwt.getClaim("tv");
            if (tv == null || tv.isNull() || tv.asInt() == null || tv.asInt() != u.tokenVersion) return null;
            long exp = jwt.getExpiresAt() == null ? 0 : jwt.getExpiresAt().getTime();
            return new WebUserImpl(u.username, u.role, permissionsOf(u.role), jwt.getId(), exp, u.tokenVersion);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 当前用户对象（用于刷新 WS 连接的权限）；token 版本已变化返回 null。 */
    WebUserImpl refresh(WebUserImpl old) {
        UserRow u = user(old.username());
        if (u == null || u.tokenVersion != old.tokenVersion() || old.expiresAt() <= System.currentTimeMillis()) return null;
        return new WebUserImpl(u.username, u.role, permissionsOf(u.role), old.jti(), old.expiresAt(), u.tokenVersion);
    }

    int sessionHours() {
        return sessionHours;
    }

    // ------------------------------------------------------------------ 游戏账号绑定码

    /** 8 位字母数字验证码（大小写不敏感），5 分钟有效。 */
    String createBindCode(UUID uuid, String name) {
        long now = System.currentTimeMillis();
        bindCodes.values().removeIf(c -> c.expiresAt < now || c.uuid.equals(uuid));
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            code = sb.toString();
        } while (bindCodes.containsKey(code));
        bindCodes.put(code, new BindCode(uuid, name, now + 5 * 60_000L));
        return code;
    }

    BindCode consumeBindCode(String code) {
        if (code == null) return null;
        BindCode c = bindCodes.remove(code.trim().toUpperCase(Locale.ROOT));
        if (c == null || c.expiresAt < System.currentTimeMillis()) return null;
        return c;
    }
}
