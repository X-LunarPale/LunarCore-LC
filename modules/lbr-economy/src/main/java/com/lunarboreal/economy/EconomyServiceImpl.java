package com.lunarboreal.economy;

import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * EconomyService 实现：全部账户常驻内存（ConcurrentHashMap），修改即时生效，
 * 脏账户与流水由定时任务异步批量写库，模块禁用时同步写回。
 * <p>
 * 金额在数据库中以字符串存储，避免 SQLite 将 DECIMAL 当作浮点导致精度丢失。
 */
public final class EconomyServiceImpl implements EconomyService {

    /** Result.error 错误码（README 中列出，供其它模块判断）。 */
    public static final String ERR_INVALID = "invalid_amount";
    public static final String ERR_FUNDS = "insufficient_funds";
    public static final String ERR_MAX = "max_balance";
    public static final String ERR_NO_ACCOUNT = "no_account";
    public static final String ERR_SAME = "same_account";
    /** 模块刚启用、账户尚未从数据库加载完成（通常只持续启动后的几百毫秒）。 */
    public static final String ERR_LOADING = "loading";

    public static final class Account {
        final UUID uuid;
        volatile String name;
        BigDecimal balance;
        final long created;
        volatile long updated;
        boolean dirty;

        Account(UUID uuid, String name, BigDecimal balance, long created, long updated) {
            this.uuid = uuid;
            this.name = name;
            this.balance = balance;
            this.created = created;
            this.updated = updated;
        }

        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public synchronized BigDecimal balance() { return balance; }
        public long updated() { return updated; }
    }

    /** 一条流水。 */
    public record Tx(long id, long time, UUID uuid, String name, BigDecimal change, BigDecimal balance,
                     String reason, String operator) {}

    private enum Op { DEPOSIT, WITHDRAW, SET }

    private final EconomyModule module;
    private final Database db;
    private final String accountsTable;
    private final String txTable;

    /** Serializes ledger mutations with flush snapshots, never held during database I/O. */
    private final Object ledgerLock = new Object();
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Queue<Tx> pendingTx = new ConcurrentLinkedQueue<>();
    /** 加载完成前请求开户/登录的玩家（uuid → 名字，未知为空串），加载后补处理。 */
    private final Map<UUID, String> pendingAccounts = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile boolean tablesReady;
    private volatile List<TopEntry> topCache = List.of();
    private volatile Map<UUID, Integer> rankCache = Map.of();

    public EconomyServiceImpl(EconomyModule module, Database db) {
        this.module = module;
        this.db = db;
        this.accountsTable = db.table("economy_accounts");
        this.txTable = db.table("economy_transactions");
    }

    private EconomySettings s() {
        return module.settings();
    }

    public String accountsTable() { return accountsTable; }

    public String txTable() { return txTable; }

    public boolean isLoaded() { return loaded; }

    // =====================================================================
    // 存储
    // =====================================================================

    /** 建表 + 全量加载（在异步线程调用）。 */
    public void init() throws SQLException {
        db.update("CREATE TABLE IF NOT EXISTS " + accountsTable + " ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "name VARCHAR(32),"
                + "balance VARCHAR(64) NOT NULL,"
                + "created BIGINT NOT NULL,"
                + "updated BIGINT NOT NULL)");
        db.update("CREATE TABLE IF NOT EXISTS " + txTable + " ("
                + "id " + db.autoIncrementPk() + ","
                + "time BIGINT NOT NULL,"
                + "uuid VARCHAR(36) NOT NULL,"
                + "name VARCHAR(32),"
                + "amount VARCHAR(64) NOT NULL,"
                + "balance VARCHAR(64) NOT NULL,"
                + "reason VARCHAR(255),"
                + "operator VARCHAR(64))");
        createIndex("CREATE INDEX " + txTable + "_uuid ON " + txTable + " (uuid)");
        createIndex("CREATE INDEX " + txTable + "_time ON " + txTable + " (time)");
        tablesReady = true;

        List<Account> all = db.query("SELECT uuid, name, balance, created, updated FROM " + accountsTable,
                rs -> new Account(UUID.fromString(rs.getString(1)), rs.getString(2),
                        EconomySettings.dec(rs.getString(3)), rs.getLong(4), rs.getLong(5)));
        // 加载完成前不会在内存中创建任何账户（见 getOrCreate），因此这里以数据库为准
        for (Account a : all) {
            accounts.put(a.uuid, a);
            if (a.name != null) nameIndex.put(a.name.toLowerCase(Locale.ROOT), a.uuid);
        }
        loaded = true;
        // 加载期间登录/请求开户的玩家，现在补建账户或更新名字
        for (Map.Entry<UUID, String> e : pendingAccounts.entrySet()) touchName(e.getKey(), e.getValue());
        pendingAccounts.clear();
        refreshTop();
        module.logger().info("已加载 " + all.size() + " 个经济账户");
    }

    private void createIndex(String sql) {
        try {
            db.update(sql);
        } catch (SQLException ignored) {
            // 索引已存在
        }
    }

    /** 把脏账户与待写流水批量写库。线程安全，失败时重新标记待写。 */
    public synchronized void flush() {
        if (!tablesReady) return;
        List<Account> dirty = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        List<Tx> txs = new ArrayList<>();
        synchronized (ledgerLock) {
        for (Account a : accounts.values()) {
            synchronized (a) {
                if (!a.dirty) continue;
                a.dirty = false;
                dirty.add(a);
                rows.add(new Object[]{a.uuid.toString(), a.name, a.balance.toPlainString(), a.created, a.updated});
            }
        }
        Tx t;
        while ((t = pendingTx.poll()) != null) txs.add(t);
        }
        if (rows.isEmpty() && txs.isEmpty()) return;

        String upsert = db.upsert(accountsTable, List.of("uuid"), List.of("uuid", "name", "balance", "created", "updated"));
        String insertTx = "INSERT INTO " + txTable + " (time, uuid, name, amount, balance, reason, operator) VALUES (?,?,?,?,?,?,?)";
        try {
            db.transaction((Connection conn) -> {
                if (!rows.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                        for (Object[] r : rows) {
                            for (int i = 0; i < r.length; i++) ps.setObject(i + 1, r[i]);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                if (!txs.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(insertTx)) {
                        for (Tx x : txs) {
                            ps.setLong(1, x.time());
                            ps.setString(2, x.uuid().toString());
                            ps.setString(3, x.name());
                            ps.setString(4, x.change().toPlainString());
                            ps.setString(5, x.balance().toPlainString());
                            ps.setString(6, cut(x.reason(), 255));
                            ps.setString(7, cut(x.operator(), 64));
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            module.logger().log(Level.WARNING, "经济数据写库失败，将在下次重试: " + e.getMessage());
            for (Account a : dirty) synchronized (a) { a.dirty = true; }
            pendingTx.addAll(txs);
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // =====================================================================
    // 账户
    // =====================================================================

    /**
     * 查找内存中的账户。全量加载完成前一律返回 null —— 加载期间绝不在内存中创建账户，
     * 避免“假账户”在刷盘时覆盖数据库中的真实余额。
     */
    public Account account(UUID uuid) {
        return uuid == null ? null : accounts.get(uuid);
    }

    /** 取得或创建账户；全量加载完成前返回 null（调用方须返回 ERR_LOADING）。 */
    public Account getOrCreate(UUID uuid, String nameHint) {
        synchronized (ledgerLock) { return getOrCreateLocked(uuid, nameHint); }
    }

    private Account getOrCreateLocked(UUID uuid, String nameHint) {
        if (uuid == null) return null;
        if (!loaded) {
            pendingAccounts.put(uuid, nameHint == null ? "" : nameHint);
            return null;
        }
        Account a = accounts.get(uuid);
        if (a != null) return a;
        String name = nameHint != null ? nameHint : guessName(uuid);
        long now = System.currentTimeMillis();
        BigDecimal init = s().initialBalance;
        Account created = new Account(uuid, name, init, now, now);
        created.dirty = true;
        Account prev = accounts.putIfAbsent(uuid, created);
        if (prev != null) return prev;
        if (name != null) nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        if (init.signum() != 0) {
            pendingTx.add(new Tx(0, now, uuid, name, init, init, "初始余额", "system"));
        }
        return created;
    }

    private String guessName(UUID uuid) {
        try {
            var rec = module.api().players().get(uuid);
            if (rec.isPresent()) return rec.get().name();
        } catch (Exception ignored) {}
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName();
    }

    /** 玩家登录时更新名字（加载完成前记录下来，加载后补处理）。 */
    public void touchName(UUID uuid, String name) {
        if (name != null && name.isEmpty()) name = null;
        Account a = getOrCreate(uuid, name);
        if (a == null) return;
        if (name != null && !name.equals(a.name)) {
            if (a.name != null) nameIndex.remove(a.name.toLowerCase(Locale.ROOT), uuid);
            synchronized (a) {
                a.name = name;
                a.dirty = true;
            }
        }
        if (name != null) nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    /** 按名字查找账户 UUID（在线玩家 → 账户名索引 → core 玩家档案）。 */
    public UUID resolve(String name) {
        if (name == null || name.isEmpty()) return null;
        var online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        UUID u = nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (u != null) return u;
        try {
            return module.api().players().uuidOf(name).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public String nameOf(UUID uuid) {
        Account a = accounts.get(uuid);
        if (a != null && a.name != null) return a.name;
        String n = guessName(uuid);
        return n != null ? n : uuid.toString();
    }

    public List<Account> allAccounts() {
        return new ArrayList<>(accounts.values());
    }

    public int accountCount() {
        return accounts.size();
    }

    public BigDecimal totalMoney() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Account a : accounts.values()) sum = sum.add(a.balance());
        return sum;
    }

    // =====================================================================
    // 余额操作（带操作者）
    // =====================================================================

    public Result deposit(UUID player, BigDecimal amount, String reason, String operator) {
        return apply(player, Op.DEPOSIT, amount, reason, operator);
    }

    public Result withdraw(UUID player, BigDecimal amount, String reason, String operator) {
        return apply(player, Op.WITHDRAW, amount, reason, operator);
    }

    public Result set(UUID player, BigDecimal amount, String reason, String operator) {
        return apply(player, Op.SET, amount, reason, operator);
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(s().decimals);
    }

    private Result apply(UUID uuid, Op op, BigDecimal raw, String reason, String operator) {
        synchronized (ledgerLock) { return applyLocked(uuid, op, raw, reason, operator); }
    }

    private Result applyLocked(UUID uuid, Op op, BigDecimal raw, String reason, String operator) {
        if (uuid == null) return Result.fail(zero(), ERR_NO_ACCOUNT);
        if (!loaded) return Result.fail(zero(), ERR_LOADING);
        Account a = getOrCreate(uuid, null);
        if (a == null) return Result.fail(zero(), ERR_LOADING);
        if (raw == null || raw.signum() < 0) return Result.fail(a.balance(), ERR_INVALID);
        EconomySettings st = s();
        // 扣款向上取整（防止 0.009 之类的小额在截断后变成免费），入账/设置向下截断
        BigDecimal amount = op == Op.WITHDRAW ? st.scaleUp(raw) : st.scale(raw);
        if (raw.signum() > 0 && amount.signum() == 0 && op != Op.SET) return Result.fail(a.balance(), ERR_INVALID);
        synchronized (a) {
            BigDecimal old = a.balance;
            BigDecimal next = switch (op) {
                case DEPOSIT -> old.add(amount);
                case WITHDRAW -> old.subtract(amount);
                case SET -> amount;
            };
            if (next.signum() < 0) return Result.fail(old, ERR_FUNDS);
            if (st.maxBalance != null && next.compareTo(st.maxBalance) > 0 && op != Op.WITHDRAW) {
                return Result.fail(old, ERR_MAX);
            }
            if (next.compareTo(old) == 0 && op != Op.SET) return Result.ok(old);
            a.balance = next;
            a.updated = System.currentTimeMillis();
            a.dirty = true;
            pendingTx.add(new Tx(0, a.updated, uuid, a.name, next.subtract(old), next, reason, operator));
            return Result.ok(next);
        }
    }

    /**
     * 转账：付款方扣除 amount + fee，收款方得到 amount。两个账户按 UUID 顺序加锁保证原子性。
     * 返回的 Result.balance 为付款方余额。
     */
    public Result transfer(UUID from, UUID to, BigDecimal raw, BigDecimal fee, String reason, String operator) {
        synchronized (ledgerLock) { return transferLocked(from, to, raw, fee, reason, operator); }
    }

    private Result transferLocked(UUID from, UUID to, BigDecimal raw, BigDecimal fee, String reason, String operator) {
        if (from == null || to == null) return Result.fail(zero(), ERR_NO_ACCOUNT);
        if (!loaded) return Result.fail(zero(), ERR_LOADING);
        Account a = getOrCreate(from, null);
        Account b = getOrCreate(to, null);
        if (a == null || b == null) return Result.fail(zero(), ERR_LOADING);
        if (from.equals(to)) return Result.fail(a.balance(), ERR_SAME);
        if (raw == null || raw.signum() <= 0) return Result.fail(a.balance(), ERR_INVALID);
        EconomySettings st = s();
        BigDecimal amount = st.scale(raw);
        if (amount.signum() <= 0) return Result.fail(a.balance(), ERR_INVALID);
        BigDecimal f = fee == null ? BigDecimal.ZERO : st.scaleUp(fee.max(BigDecimal.ZERO));
        Account first = from.compareTo(to) < 0 ? a : b;
        Account second = first == a ? b : a;
        synchronized (first) {
            synchronized (second) {
                BigDecimal cost = amount.add(f);
                if (a.balance.compareTo(cost) < 0) return Result.fail(a.balance, ERR_FUNDS);
                BigDecimal nb = b.balance.add(amount);
                if (st.maxBalance != null && nb.compareTo(st.maxBalance) > 0) return Result.fail(a.balance, ERR_MAX);
                long now = System.currentTimeMillis();
                a.balance = a.balance.subtract(cost);
                b.balance = nb;
                a.updated = now;
                b.updated = now;
                a.dirty = true;
                b.dirty = true;
                String r = reason == null ? "转账" : reason;
                pendingTx.add(new Tx(0, now, from, a.name, cost.negate(), a.balance,
                        r + " → " + b.name + (f.signum() > 0 ? "（手续费 " + f.toPlainString() + "）" : ""), operator));
                pendingTx.add(new Tx(0, now, to, b.name, amount, b.balance, r + " ← " + a.name, operator));
                return Result.ok(a.balance);
            }
        }
    }

    // =====================================================================
    // 排行
    // =====================================================================

    public void refreshTop() {
        List<Account> list = new ArrayList<>(accounts.values());
        List<TopEntry> top = new ArrayList<>(list.size());
        for (Account a : list) top.add(new TopEntry(a.uuid, a.name == null ? a.uuid.toString() : a.name, a.balance()));
        top.sort(Comparator.comparing(TopEntry::balance).reversed().thenComparing(TopEntry::name));
        Map<UUID, Integer> ranks = new HashMap<>(top.size() * 2);
        for (int i = 0; i < top.size(); i++) ranks.put(top.get(i).uuid(), i + 1);
        topCache = List.copyOf(top);
        rankCache = ranks;
    }

    public List<TopEntry> topAll() {
        if (topCache.isEmpty() && !accounts.isEmpty()) refreshTop();
        return topCache;
    }

    /** 0 表示未上榜。 */
    public int rank(UUID uuid) {
        if (rankCache.isEmpty() && !accounts.isEmpty()) refreshTop();
        return rankCache.getOrDefault(uuid, 0);
    }

    // =====================================================================
    // 流水查询（异步线程调用）
    // =====================================================================

    public List<Tx> queryTx(UUID player, String keyword, int offset, int limit) throws SQLException {
        flush();
        StringBuilder sql = new StringBuilder("SELECT id, time, uuid, name, amount, balance, reason, operator FROM " + txTable + " WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (player != null) { sql.append(" AND uuid = ?"); params.add(player.toString()); }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR reason LIKE ? OR operator LIKE ? OR uuid = ?)");
            String like = "%" + keyword + "%";
            params.add(like); params.add(like); params.add(like); params.add(keyword);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return db.query(sql.toString(), rs -> new Tx(rs.getLong(1), rs.getLong(2), UUID.fromString(rs.getString(3)),
                rs.getString(4), EconomySettings.dec(rs.getString(5)), EconomySettings.dec(rs.getString(6)),
                rs.getString(7), rs.getString(8)), params.toArray());
    }

    public long countTx(UUID player, String keyword) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM " + txTable + " WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (player != null) { sql.append(" AND uuid = ?"); params.add(player.toString()); }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR reason LIKE ? OR operator LIKE ? OR uuid = ?)");
            String like = "%" + keyword + "%";
            params.add(like); params.add(like); params.add(like); params.add(keyword);
        }
        return db.queryOne(sql.toString(), rs -> rs.getLong(1), params.toArray()).orElse(0L);
    }

    /** 自 since 起的流水条数与总流转金额（绝对值求和/2 近似为交易额，这里直接返回正向变动和）。 */
    public BigDecimal[] txSince(long since) throws SQLException {
        flush();
        List<String> amounts = db.query("SELECT amount FROM " + txTable + " WHERE time >= ?", rs -> rs.getString(1), since);
        BigDecimal in = BigDecimal.ZERO;
        for (String s : amounts) {
            BigDecimal v = EconomySettings.dec(s);
            if (v.signum() > 0) in = in.add(v);
        }
        return new BigDecimal[]{BigDecimal.valueOf(amounts.size()), in};
    }

    // =====================================================================
    // EconomyService
    // =====================================================================

    @Override
    public String currencySingular() { return s().singular; }

    @Override
    public String currencyPlural() { return s().plural; }

    @Override
    public String format(BigDecimal amount) { return s().format(amount == null ? BigDecimal.ZERO : amount); }

    @Override
    public boolean hasAccount(UUID player) { return account(player) != null; }

    @Override
    public void createAccount(UUID player, String name) { if (player != null) getOrCreate(player, name); }

    @Override
    public BigDecimal balance(UUID player) {
        Account a = account(player);
        return a == null ? BigDecimal.ZERO.setScale(s().decimals) : a.balance();
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        if (amount == null) return false;
        return balance(player).compareTo(amount) >= 0;
    }

    @Override
    public Result withdraw(UUID player, BigDecimal amount, String reason) {
        return withdraw(player, amount, reason, "api");
    }

    @Override
    public Result deposit(UUID player, BigDecimal amount, String reason) {
        return deposit(player, amount, reason, "api");
    }

    @Override
    public Result set(UUID player, BigDecimal amount, String reason) {
        return set(player, amount, reason, "api");
    }

    @Override
    public Result transfer(UUID from, UUID to, BigDecimal amount, String reason) {
        return transfer(from, to, amount, BigDecimal.ZERO, reason, "api");
    }

    @Override
    public List<TopEntry> top(int limit) {
        List<TopEntry> all = topAll();
        return all.size() <= limit ? all : all.subList(0, Math.max(0, limit));
    }
}
