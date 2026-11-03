package com.lunarboreal.core.database;

import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.database.RowMapper;
import com.lunarboreal.api.database.SqlFunction;
import com.lunarboreal.core.LunarCorePlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * HikariCP 实现的共享数据库。
 * <ul>
 *   <li>SQLite：WAL + busy_timeout，JVM 内用公平锁串行化写操作，避免 SQLITE_BUSY；</li>
 *   <li>同一线程在 {@link #transaction} 内调用 update/query/insert 时复用事务连接，避免死锁；</li>
 *   <li>异步操作使用独立线程池，关闭时等待队列执行完毕。</li>
 * </ul>
 */
public final class SqlDatabase implements Database {

    private final LunarCorePlugin plugin;
    private final Type type;
    private final String prefix;
    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    /** SQLite 写锁（MySQL 不使用）。 */
    private final ReentrantLock writeLock = new ReentrantLock(true);
    private final ThreadLocal<Connection> txConnection = new ThreadLocal<>();

    public SqlDatabase(LunarCorePlugin plugin) {
        this.plugin = plugin;
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("database");
        if (cfg == null) cfg = plugin.getConfig().createSection("database");
        String t = cfg.getString("type", "sqlite").trim().toLowerCase();
        this.type = (t.equals("mysql") || t.equals("mariadb")) ? Type.MYSQL : Type.SQLITE;
        String pfx = cfg.getString("table-prefix", "lbr_");
        if (pfx == null || !pfx.matches("[A-Za-z0-9_]{0,32}")) {
            plugin.getLogger().warning("database.table-prefix 只能包含字母、数字、下划线（≤32 字符），已回退为 lbr_");
            pfx = "lbr_";
        }
        this.prefix = pfx;

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("LunarCore-DB");
        hc.setConnectionTimeout(cfg.getLong("pool.connection-timeout", 10000));
        if (type == Type.SQLITE) {
            // sqlite-jdbc 需把原生库释放到临时目录；面板服/受限账户的系统临时目录常不可写，统一改到插件数据目录
            if (System.getProperty("org.sqlite.tmpdir") == null) {
                File nativeDir = new File(plugin.getDataFolder(), ".native");
                nativeDir.mkdirs();
                System.setProperty("org.sqlite.tmpdir", nativeDir.getAbsolutePath());
            }
            File file = new File(plugin.getDataFolder(), cfg.getString("sqlite.file", "data.db"));
            file.getParentFile().mkdirs();
            hc.setDriverClassName("org.sqlite.JDBC");
            hc.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath().replace('\\', '/'));
            hc.addDataSourceProperty("journal_mode", "WAL");
            hc.addDataSourceProperty("synchronous", "NORMAL");
            hc.addDataSourceProperty("busy_timeout", "15000");
            hc.addDataSourceProperty("foreign_keys", "true");
            hc.setConnectionInitSql("PRAGMA busy_timeout=15000");
            // WAL 下允许并发读；写由 writeLock 串行化
            hc.setMaximumPoolSize(Math.max(2, Math.min(cfg.getInt("pool.maximum-pool-size", 10), 6)));
            hc.setMinimumIdle(1);
        } else {
            hc.setDriverClassName(resolveMysqlDriver());
            String params = cfg.getString("mysql.parameters", "");
            hc.setJdbcUrl("jdbc:mysql://" + cfg.getString("mysql.host", "127.0.0.1") + ":" + cfg.getInt("mysql.port", 3306)
                    + "/" + cfg.getString("mysql.database", "lunarboreal") + (params == null || params.isBlank() ? "" : "?" + params));
            hc.setUsername(cfg.getString("mysql.username", "root"));
            hc.setPassword(cfg.getString("mysql.password", ""));
            hc.setMaximumPoolSize(Math.max(2, cfg.getInt("pool.maximum-pool-size", 10)));
            hc.setMinimumIdle(Math.max(0, cfg.getInt("pool.minimum-idle", 2)));
            hc.addDataSourceProperty("cachePrepStmts", "true");
            hc.addDataSourceProperty("prepStmtCacheSize", "250");
            hc.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hc.addDataSourceProperty("useServerPrepStmts", "true");
            hc.addDataSourceProperty("rewriteBatchedStatements", "true");
        }

        // Paper 通过 plugin.yml libraries 加载驱动：驱动类只在插件类加载器可见，Hikari 通过 TCCL 加载驱动
        Thread th = Thread.currentThread();
        ClassLoader old = th.getContextClassLoader();
        th.setContextClassLoader(plugin.getClass().getClassLoader());
        try {
            this.dataSource = new HikariDataSource(hc);
        } finally {
            th.setContextClassLoader(old);
        }

        int threads = Math.max(1, plugin.getConfig().getInt("database.async-threads", type == Type.SQLITE ? 2 : 4));
        AtomicInteger counter = new AtomicInteger();
        ClassLoader pluginLoader = plugin.getClass().getClassLoader();
        ThreadFactory tf = r -> {
            Thread thread = new Thread(r, "LunarCore-DB-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(pluginLoader);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(threads, tf);

        if (type == Type.SQLITE) {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            } catch (SQLException e) {
                plugin.getLogger().warning("设置 SQLite WAL 模式失败: " + e.getMessage());
            }
        }
    }

    private String resolveMysqlDriver() {
        ClassLoader cl = plugin.getClass().getClassLoader();
        for (String name : List.of("com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver", "com.mysql.jdbc.Driver")) {
            try {
                Class.forName(name, false, cl);
                return name;
            } catch (ClassNotFoundException ignored) {
            }
        }
        return "com.mysql.cj.jdbc.Driver";
    }

    // ------------------------------------------------------------------

    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("数据库异步任务 30 秒内未完成，强制关闭");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    public ExecutorService executor() {
        return executor;
    }

    public String describe() {
        return type == Type.SQLITE ? "SQLite" : "MySQL";
    }

    // ------------------------------------------------------------------

    @Override
    public Type type() {
        return type;
    }

    @Override
    public String table(String name) {
        return prefix + name;
    }

    @Override
    public String autoIncrementPk() {
        return type == Type.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT PRIMARY KEY AUTO_INCREMENT";
    }

    @Override
    public String upsert(String table, List<String> keyColumns, List<String> columns) {
        StringJoiner cols = new StringJoiner(", ");
        StringJoiner qs = new StringJoiner(", ");
        for (String c : columns) {
            cols.add(c);
            qs.add("?");
        }
        List<String> others = new ArrayList<>();
        for (String c : columns) if (!keyColumns.contains(c)) others.add(c);
        if (type == Type.SQLITE) {
            StringBuilder sb = new StringBuilder("INSERT INTO ").append(table).append(" (").append(cols).append(") VALUES (")
                    .append(qs).append(") ON CONFLICT(").append(String.join(", ", keyColumns)).append(") DO ");
            if (others.isEmpty()) {
                sb.append("NOTHING");
            } else {
                StringJoiner set = new StringJoiner(", ");
                for (String c : others) set.add(c + " = excluded." + c);
                sb.append("UPDATE SET ").append(set);
            }
            return sb.toString();
        }
        if (others.isEmpty()) {
            return "INSERT IGNORE INTO " + table + " (" + cols + ") VALUES (" + qs + ")";
        }
        StringJoiner set = new StringJoiner(", ");
        for (String c : others) set.add(c + " = VALUES(" + c + ")");
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + qs + ") ON DUPLICATE KEY UPDATE " + set;
    }

    @Override
    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    // ------------------------------------------------------------------

    private void lockWrite() {
        if (type == Type.SQLITE) writeLock.lock();
    }

    private void unlockWrite() {
        if (type == Type.SQLITE) writeLock.unlock();
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof java.util.UUID u) p = u.toString();
            else if (p instanceof Enum<?> e) p = e.name();
            ps.setObject(i + 1, p);
        }
    }

    @Override
    public int update(String sql, Object... params) throws SQLException {
        Connection tx = txConnection.get();
        if (tx != null) {
            try (PreparedStatement ps = tx.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        }
        lockWrite();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } finally {
            unlockWrite();
        }
    }

    @Override
    public long insert(String sql, Object... params) throws SQLException {
        Connection tx = txConnection.get();
        if (tx != null) return doInsert(tx, sql, params);
        lockWrite();
        try (Connection c = dataSource.getConnection()) {
            return doInsert(c, sql, params);
        } finally {
            unlockWrite();
        }
    }

    private long doInsert(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs != null && rs.next()) return rs.getLong(1);
            } catch (SQLException ignored) {
                // 部分驱动不支持，回退
            }
        }
        if (type == Type.SQLITE) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) return rs.getLong(1);
            }
        } else {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT LAST_INSERT_ID()")) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        Connection tx = txConnection.get();
        if (tx != null) return doQuery(tx, sql, mapper, params);
        try (Connection c = dataSource.getConnection()) {
            return doQuery(c, sql, mapper, params);
        }
    }

    private static <T> List<T> doQuery(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        }
    }

    @Override
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0));
    }

    @Override
    public <T> T transaction(SqlFunction<Connection, T> work) throws SQLException {
        Connection existing = txConnection.get();
        if (existing != null) return work.apply(existing); // 嵌套事务并入外层
        lockWrite();
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            txConnection.set(c);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                try {
                    c.rollback();
                } catch (SQLException re) {
                    e.addSuppressed(re);
                }
                throw e;
            } finally {
                txConnection.remove();
                try {
                    c.setAutoCommit(auto);
                } catch (SQLException ignored) {
                }
            }
        } finally {
            unlockWrite();
        }
    }

    @Override
    public <T> CompletableFuture<T> async(SqlFunction<Database, T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(work.apply(this));
                } catch (Throwable t) {
                    if (plugin.isDebug()) plugin.getLogger().log(Level.WARNING, "异步数据库任务失败", t);
                    future.completeExceptionally(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 线程池已关闭（插件正在关闭）：同步执行，保证写入不丢失
            try {
                future.complete(work.apply(this));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
        return future;
    }

    @Override
    public CompletableFuture<Integer> updateAsync(String sql, Object... params) {
        CompletableFuture<Integer> f = async(db -> db.update(sql, params));
        f.exceptionally(t -> {
            plugin.getLogger().warning("SQL 执行失败: " + t.getMessage() + " | " + sql);
            return null;
        });
        return f;
    }

    @Override
    public <T> CompletableFuture<List<T>> queryAsync(String sql, RowMapper<T> mapper, Object... params) {
        return async(db -> db.query(sql, mapper, params));
    }

    /** 建索引，忽略"已存在"错误（MySQL 不支持 IF NOT EXISTS）。 */
    public void createIndexQuietly(String name, String table, String columns) {
        String sql = type == Type.SQLITE
                ? "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")"
                : "CREATE INDEX " + name + " ON " + table + " (" + columns + ")";
        try {
            update(sql);
        } catch (SQLException ignored) {
        }
    }
}
