package com.lunarboreal.ban;

import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.service.BanService;
import com.lunarboreal.api.service.BanService.Punishment;
import com.lunarboreal.api.service.BanService.Type;
import org.bukkit.Bukkit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 处罚数据层：表 lbr_ban_punishments + 活跃处罚内存缓存。
 * <ul>
 *   <li>ID 分配：启动时读取 MAX(id) 作为种子，之后在内存中递增并以显式 ID 插入，
 *       使 {@link #create} 可在主线程立即返回带 ID 的记录而不阻塞。</li>
 *   <li>所有写操作（建表、插入、覆盖、撤销、过期标记）进入<b>单线程有序队列</b>，
 *       保证"封禁 → 立即解封"等操作在数据库中的执行顺序与内存一致。</li>
 *   <li>多服共享 MySQL 出现主键冲突时，在写线程内刷新种子并换号重试，记录旧 ID → 新 ID 映射，
 *       之后针对旧 ID 的撤销会自动改写到新 ID。</li>
 *   <li>multi-server.enabled 时定时增量同步其它服的新增/撤销处罚。</li>
 * </ul>
 */
final class PunishmentStore {

    static final String COLUMNS = "id, type, uuid, name, ip, reason, operator, created, expires, active, revoked_by, revoked_at, server";

    @FunctionalInterface
    private interface SqlTask { void run() throws Exception; }

    private final BanModule module;
    private final Database db;
    private final String table;

    private final Map<UUID, Punishment> bans = new ConcurrentHashMap<>();
    private final Map<String, Punishment> ipBans = new ConcurrentHashMap<>();
    private final Map<UUID, Punishment> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, List<Punishment>> warns = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(0);
    private final CompletableFuture<Void> loaded = new CompletableFuture<>();

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LunarCore-Ban-Writer");
        t.setDaemon(true);
        return t;
    });
    /** 插入冲突换号：旧 ID → 新 ID。 */
    private final Map<Long, Long> idRemap = new ConcurrentHashMap<>();
    /** 本服撤销过的 ID（防止多服同步把刚撤销的记录重新加入缓存）。 */
    private final Set<Long> recentlyRevoked = ConcurrentHashMap.newKeySet();
    private volatile long maxSeenId;
    private volatile long lastSyncTime = System.currentTimeMillis();

    PunishmentStore(BanModule module, Database db) {
        this.module = module;
        this.db = db;
        this.table = db.table("ban_punishments");
    }

    String table() { return table; }

    Database db() { return db; }

    CompletableFuture<Void> loadedFuture() { return loaded; }

    boolean isLoaded() { return loaded.isDone() && !loaded.isCompletedExceptionally(); }

    // ======================================================================
    // 写队列
    // ======================================================================

    private CompletableFuture<Void> write(String desc, SqlTask task) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Runnable r = () -> {
            try {
                task.run();
                f.complete(null);
            } catch (Throwable t) {
                module.logger().log(Level.WARNING, "处罚数据写入失败（" + desc + "）", t);
                f.completeExceptionally(t);
            }
        };
        try {
            writer.execute(r);
        } catch (RejectedExecutionException e) {
            r.run(); // 已关闭：同步执行，保证不丢失
        }
        return f;
    }

    private long resolve(long id) {
        Long n = idRemap.get(id);
        while (n != null) {
            id = n;
            n = idRemap.get(id);
        }
        return id;
    }

    /** 模块禁用时调用：等待队列中的写入完成（最多 10 秒）。 */
    void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                module.logger().warning("处罚写入队列 10 秒内未完成，剩余任务被丢弃");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ======================================================================
    // 初始化
    // ======================================================================

    void init() {
        final int[] count = {0};
        write("init", () -> {
            db.update("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id BIGINT PRIMARY KEY, "
                    + "type VARCHAR(16) NOT NULL, "
                    + "uuid VARCHAR(36), "
                    + "name VARCHAR(32), "
                    + "ip VARCHAR(64), "
                    + "reason VARCHAR(512), "
                    + "operator VARCHAR(64), "
                    + "created BIGINT NOT NULL, "
                    + "expires BIGINT, "
                    + "active INT NOT NULL DEFAULT 1, "
                    + "revoked_by VARCHAR(64), "
                    + "revoked_at BIGINT, "
                    + "server VARCHAR(64))");
            for (String col : new String[]{"uuid", "ip", "active", "created", "revoked_at"}) {
                try {
                    db.update("CREATE INDEX idx_" + table + "_" + col + " ON " + table + " (" + col + ")");
                } catch (SQLException ignored) {
                    // 索引已存在
                }
            }
            long max = db.queryOne("SELECT MAX(id) AS m FROM " + table, rs -> rs.getLong("m")).orElse(0L);
            nextId.updateAndGet(x -> Math.max(x, max));
            maxSeenId = max;
            List<Punishment> active = db.query("SELECT " + COLUMNS + " FROM " + table
                    + " WHERE active = 1 AND type IN ('BAN','IPBAN','MUTE','WARN')", PunishmentStore::map);
            long now = System.currentTimeMillis();
            for (Punishment p : active) {
                if (p.expiresAt() != null && p.expiresAt() < now) {
                    db.update("UPDATE " + table + " SET active = 0 WHERE id = ?", p.id());
                    continue;
                }
                cachePut(p);
            }
            count[0] = active.size();
        }).whenComplete((v, err) -> {
            if (err != null) {
                loaded.completeExceptionally(err);
            } else {
                module.logger().info("已加载 " + count[0] + " 条活跃处罚。");
                loaded.complete(null);
            }
        });
    }

    /** 预登录线程（异步）调用：等待缓存加载，超时返回 false。 */
    boolean awaitLoaded(long millis) {
        try {
            loaded.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Punishment map(ResultSet rs) throws SQLException {
        String u = rs.getString("uuid");
        long exp = rs.getLong("expires");
        Long expires = rs.wasNull() ? null : exp;
        Type type;
        try { type = Type.valueOf(rs.getString("type")); } catch (Exception e) { type = Type.WARN; }
        return new Punishment(rs.getLong("id"), type,
                u == null || u.isEmpty() ? null : UUID.fromString(u),
                rs.getString("name"), rs.getString("ip"), rs.getString("reason"), rs.getString("operator"),
                rs.getLong("created"), expires, rs.getInt("active") == 1, rs.getString("revoked_by"));
    }

    // ======================================================================
    // 缓存
    // ======================================================================

    private void cachePut(Punishment p) {
        switch (p.type()) {
            case BAN -> { if (p.target() != null) bans.put(p.target(), p); }
            case IPBAN -> { if (p.ip() != null) ipBans.put(p.ip(), p); }
            case MUTE -> { if (p.target() != null) mutes.put(p.target(), p); }
            case WARN -> {
                if (p.target() != null) {
                    List<Punishment> l = warns.computeIfAbsent(p.target(), k -> new CopyOnWriteArrayList<>());
                    if (l.stream().noneMatch(x -> x.id() == p.id())) l.add(p);
                }
            }
            default -> {}
        }
    }

    private void cacheRemove(Punishment p) {
        switch (p.type()) {
            case BAN -> { if (p.target() != null) bans.remove(p.target(), p); }
            case IPBAN -> { if (p.ip() != null) ipBans.remove(p.ip(), p); }
            case MUTE -> { if (p.target() != null) mutes.remove(p.target(), p); }
            case WARN -> {
                if (p.target() != null) {
                    List<Punishment> l = warns.get(p.target());
                    if (l != null) { l.removeIf(x -> x.id() == p.id()); if (l.isEmpty()) warns.remove(p.target(), l); }
                }
            }
            default -> {}
        }
    }

    private <K> Optional<Punishment> lookup(Map<K, Punishment> map, K key) {
        if (key == null) return Optional.empty();
        Punishment p = map.get(key);
        if (p == null) return Optional.empty();
        if (p.isExpired()) {
            if (map.remove(key, p)) markExpired(p.id());
            return Optional.empty();
        }
        return Optional.of(p);
    }

    Optional<Punishment> activeBan(UUID uuid) { return lookup(bans, uuid); }

    Optional<Punishment> activeIpBan(String ip) { return lookup(ipBans, ip); }

    Optional<Punishment> activeMute(UUID uuid) { return lookup(mutes, uuid); }

    List<Punishment> activeWarns(UUID uuid) {
        if (uuid == null) return List.of();
        List<Punishment> l = warns.get(uuid);
        if (l == null) return List.of();
        List<Punishment> out = new ArrayList<>();
        for (Punishment p : l) {
            if (p.isExpired()) { l.remove(p); markExpired(p.id()); } else out.add(p);
        }
        return out;
    }

    int warnCount(UUID uuid) { return activeWarns(uuid).size(); }

    Collection<Punishment> activeBans() { return bans.values(); }

    Collection<Punishment> activeIpBans() { return ipBans.values(); }

    Collection<Punishment> activeMutes() { return mutes.values(); }

    int activeWarnTotal() { return warns.values().stream().mapToInt(List::size).sum(); }

    /** 查找活跃缓存中的处罚（自动解析换号后的 ID）。 */
    Optional<Punishment> findActive(long id) {
        long rid = resolve(id);
        for (Punishment p : bans.values()) if (p.id() == rid) return Optional.of(p);
        for (Punishment p : ipBans.values()) if (p.id() == rid) return Optional.of(p);
        for (Punishment p : mutes.values()) if (p.id() == rid) return Optional.of(p);
        for (List<Punishment> l : warns.values()) for (Punishment p : l) if (p.id() == rid) return Optional.of(p);
        return Optional.empty();
    }

    /** 周期清理（异步线程）：从缓存移除过期处罚并在数据库标记失效。 */
    void sweepExpired() {
        sweep(bans);
        sweep(ipBans);
        sweep(mutes);
        for (UUID u : new ArrayList<>(warns.keySet())) activeWarns(u);
    }

    private <K> void sweep(Map<K, Punishment> map) {
        for (Map.Entry<K, Punishment> e : new ArrayList<>(map.entrySet())) {
            if (e.getValue().isExpired() && map.remove(e.getKey(), e.getValue())) markExpired(e.getValue().id());
        }
    }

    private void markExpired(long id) {
        write("expire #" + id, () -> db.update("UPDATE " + table + " SET active = 0 WHERE id = ? AND active = 1", resolve(id)));
    }

    // ======================================================================
    // 多服同步
    // ======================================================================

    /**
     * 增量同步其它服写入的新处罚与撤销（异步线程调用，阻塞查询）。
     * @return 新加入缓存的处罚（调用方负责在主线程执行踢出/提示）
     */
    List<Punishment> syncRemote() {
        if (!isLoaded()) return List.of();
        List<Punishment> added = new ArrayList<>();
        long since = lastSyncTime;
        long now = System.currentTimeMillis();
        try {
            List<Punishment> rows = db.query("SELECT " + COLUMNS + " FROM " + table + " WHERE id > ? ORDER BY id",
                    PunishmentStore::map, maxSeenId);
            for (Punishment p : rows) {
                if (p.id() > maxSeenId) maxSeenId = p.id();
                nextId.updateAndGet(x -> Math.max(x, p.id()));
                if (!p.active() || p.isExpired() || p.type() == Type.KICK) continue;
                if (recentlyRevoked.contains(p.id()) || findActive(p.id()).isPresent()) continue;
                cachePut(p);
                added.add(p);
            }
            List<Long> revoked = db.query("SELECT id FROM " + table + " WHERE revoked_at >= ?", rs -> rs.getLong("id"), since - 5000);
            for (Long id : revoked) findActive(id).ifPresent(this::cacheRemove);
            lastSyncTime = now;
        } catch (SQLException e) {
            module.logger().log(Level.WARNING, "多服处罚同步失败", e);
        }
        if (recentlyRevoked.size() > 10_000) recentlyRevoked.clear();
        return added;
    }

    // ======================================================================
    // 写入
    // ======================================================================

    /**
     * 创建处罚（线程安全，不阻塞）。同类型旧的活跃处罚会被覆盖（标记撤销）。
     */
    Punishment create(Type type, UUID target, String name, String ip, String reason, String operator, Long durationMillis) {
        long now = System.currentTimeMillis();
        Long expires = (durationMillis == null || type == Type.KICK) ? null : now + durationMillis;
        boolean active = type != Type.KICK;
        long id = nextId.incrementAndGet();
        Punishment p = new Punishment(id, type, target, name, ip, reason, operator, now, expires, active, null);
        Punishment prev = switch (type) {
            case BAN -> target == null ? null : bans.put(target, p);
            case IPBAN -> ip == null ? null : ipBans.put(ip, p);
            case MUTE -> target == null ? null : mutes.put(target, p);
            case WARN -> { cachePut(p); yield null; }
            default -> null;
        };
        if (prev != null) {
            long prevId = prev.id();
            recentlyRevoked.add(prevId);
            write("override #" + prevId, () -> db.update("UPDATE " + table + " SET active = 0, revoked_by = ?, revoked_at = ? WHERE id = ?",
                    truncate(operator + " (覆盖)", 64), now, resolve(prevId)));
        }
        write("insert #" + id, () -> insertWithRetry(p));
        return p;
    }

    /** 写线程内执行：插入，主键冲突时换号重试（最多 3 次）。 */
    private void insertWithRetry(Punishment original) throws SQLException {
        Punishment p = original;
        for (int attempt = 0; ; attempt++) {
            try {
                db.update("INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        p.id(), p.type().name(), p.target() == null ? null : p.target().toString(),
                        truncate(p.targetName(), 32), truncate(p.ip(), 64), truncate(p.reason(), 512), truncate(p.operator(), 64),
                        p.createdAt(), p.expiresAt(), p.active() ? 1 : 0, null, null, truncate(module.serverName(), 64));
                return;
            } catch (SQLException e) {
                if (attempt >= 3) throw e;
                long max = db.queryOne("SELECT MAX(id) AS m FROM " + table, rs -> rs.getLong("m")).orElse(0L);
                nextId.updateAndGet(x -> Math.max(x, max));
                long newId = nextId.incrementAndGet();
                Punishment np = new Punishment(newId, p.type(), p.target(), p.targetName(), p.ip(), p.reason(),
                        p.operator(), p.createdAt(), p.expiresAt(), p.active(), p.revokedBy());
                idRemap.put(p.id(), newId);
                // 仍在缓存中才替换；若已被撤销/覆盖，排在后面的撤销任务会通过 idRemap 作用到新 ID
                if (p.active() && findActiveExact(p.id())) {
                    cacheRemove(p);
                    cachePut(np);
                }
                if (recentlyRevoked.contains(p.id())) recentlyRevoked.add(newId);
                module.logger().warning("处罚 #" + p.id() + " 主键冲突，改用 #" + newId);
                p = np;
            }
        }
    }

    private boolean findActiveExact(long id) {
        for (Punishment x : bans.values()) if (x.id() == id) return true;
        for (Punishment x : ipBans.values()) if (x.id() == id) return true;
        for (Punishment x : mutes.values()) if (x.id() == id) return true;
        for (List<Punishment> l : warns.values()) for (Punishment x : l) if (x.id() == id) return true;
        return false;
    }

    /**
     * 撤销处罚。活跃缓存中的处罚立即生效并排队写库，返回 true；
     * 否则非主线程时等待写队列执行结果，主线程时排队写库并返回 false。
     */
    boolean revoke(long id, String operator) {
        long now = System.currentTimeMillis();
        Optional<Punishment> cached = findActive(id);
        String sql = "UPDATE " + table + " SET active = 0, revoked_by = ?, revoked_at = ? WHERE id = ? AND active = 1";
        String op = truncate(operator, 64);
        recentlyRevoked.add(id);
        if (cached.isPresent()) {
            cacheRemove(cached.get());
            recentlyRevoked.add(cached.get().id());
            write("revoke #" + id, () -> db.update(sql, op, now, resolve(id)));
            return true;
        }
        final int[] rows = {0};
        CompletableFuture<Void> f = write("revoke #" + id, () -> rows[0] = db.update(sql, op, now, resolve(id)));
        if (Bukkit.isPrimaryThread()) return false;
        try {
            f.get(10, TimeUnit.SECONDS);
            return rows[0] > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 同步查询某玩家的全部历史（会阻塞，请在异步线程调用）。 */
    List<Punishment> historySync(UUID uuid) {
        try {
            return db.query("SELECT " + COLUMNS + " FROM " + table + " WHERE uuid = ? ORDER BY id DESC", PunishmentStore::map, uuid.toString());
        } catch (SQLException e) {
            module.logger().log(Level.WARNING, "查询处罚历史失败", e);
            return List.of();
        }
    }

    CompletableFuture<List<Punishment>> historyAsync(UUID uuid) {
        return db.queryAsync("SELECT " + COLUMNS + " FROM " + table + " WHERE uuid = ? ORDER BY id DESC", PunishmentStore::map, uuid.toString());
    }

    /** 直接查库（预登录缓存未就绪或多服模式时使用，阻塞）。 */
    Optional<Punishment> queryActiveSync(Type type, String column, String value) {
        try {
            long now = System.currentTimeMillis();
            return db.queryOne("SELECT " + COLUMNS + " FROM " + table + " WHERE type = ? AND " + column + " = ? AND active = 1"
                            + " AND (expires IS NULL OR expires > ?) ORDER BY id DESC",
                    PunishmentStore::map, type.name(), value, now);
        } catch (SQLException e) {
            module.logger().log(Level.WARNING, "查询封禁失败", e);
            return Optional.empty();
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** BanService 适配。 */
    BanService asService() {
        return new BanService() {
            @Override public Optional<Punishment> activeBan(UUID player) { return PunishmentStore.this.activeBan(player); }
            @Override public Optional<Punishment> activeIpBan(String ip) { return PunishmentStore.this.activeIpBan(ip); }
            @Override public Optional<Punishment> activeMute(UUID player) { return PunishmentStore.this.activeMute(player); }
            @Override public Punishment punish(Type type, UUID target, String targetName, String ip, String reason, String operator, Long durationMillis) {
                return module.punishFromApi(type, target, targetName, ip, reason, operator, durationMillis);
            }
            @Override public boolean revoke(long punishmentId, String operator) {
                boolean ok = PunishmentStore.this.revoke(punishmentId, operator);
                module.audit(operator == null ? "api" : operator, "revoke", "#" + punishmentId);
                return ok;
            }
            @Override public List<Punishment> history(UUID player) { return historySync(player); }
        };
    }
}
