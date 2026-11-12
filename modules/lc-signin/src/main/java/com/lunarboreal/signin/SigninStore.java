package com.lunarboreal.signin;

import com.lunarboreal.api.database.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 签到数据：表 lc_signin_records（每次签到一行）与 lc_signin_players（汇总，供排行）。
 * 在线玩家的全部签到日期缓存在内存中，连续/累计/本月数据均由缓存计算。
 */
public final class SigninStore {

    /** 单个玩家缓存。dates: 日期 → 是否补签。 */
    public static final class PlayerData {
        public final UUID uuid;
        public volatile String name;
        final NavigableMap<LocalDate, Boolean> dates = new ConcurrentSkipListMap<>();

        PlayerData(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public boolean signed(LocalDate d) { return dates.containsKey(d); }

        public boolean isMakeup(LocalDate d) { return Boolean.TRUE.equals(dates.get(d)); }

        public int total() { return dates.size(); }

        /** 当前连续天数：以今天（已签）或昨天为终点向前连续的天数。 */
        public int streak(LocalDate today) {
            LocalDate d = dates.containsKey(today) ? today : today.minusDays(1);
            int n = 0;
            while (dates.containsKey(d)) {
                n++;
                d = d.minusDays(1);
            }
            return n;
        }

        public int monthCount(YearMonth ym) {
            return dates.subMap(ym.atDay(1), true, ym.atEndOfMonth(), true).size();
        }

        public int makeupCount(YearMonth ym) {
            int n = 0;
            for (Boolean b : dates.subMap(ym.atDay(1), true, ym.atEndOfMonth(), true).values()) if (b) n++;
            return n;
        }

        public LocalDate last() { return dates.isEmpty() ? null : dates.lastKey(); }
    }

    public record Record(long id, long time, UUID uuid, String name, String date, boolean makeup) {}

    public record Summary(UUID uuid, String name, int streak, int total, String lastDate) {}

    private final Database db;
    public final String records;
    public final String players;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public SigninStore(Database db) {
        this.db = db;
        this.records = db.table("signin_records");
        this.players = db.table("signin_players");
    }

    public void init() throws SQLException {
        db.update("CREATE TABLE IF NOT EXISTS " + records + " ("
                + "id " + db.autoIncrementPk() + ","
                + "uuid VARCHAR(36) NOT NULL,"
                + "name VARCHAR(32),"
                + "date VARCHAR(10) NOT NULL,"
                + "time BIGINT NOT NULL,"
                + "makeup INT NOT NULL DEFAULT 0)");
        db.update("CREATE TABLE IF NOT EXISTS " + players + " ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "name VARCHAR(32),"
                + "streak INT NOT NULL DEFAULT 0,"
                + "total INT NOT NULL DEFAULT 0,"
                + "last_date VARCHAR(10),"
                + "updated BIGINT NOT NULL)");
        try {
            db.update("CREATE UNIQUE INDEX " + records + "_ud ON " + records + " (uuid, date)");
        } catch (SQLException ignored) {}
        try {
            db.update("CREATE INDEX " + records + "_date ON " + records + " (date)");
        } catch (SQLException ignored) {}
    }

    public PlayerData get(UUID uuid) {
        return cache.get(uuid);
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    public void clearCache() {
        cache.clear();
    }

    /** 异步线程调用：从数据库读取玩家全部签到日期。 */
    public PlayerData load(UUID uuid, String name) throws SQLException {
        PlayerData d = new PlayerData(uuid, name);
        List<Object[]> rows = db.query("SELECT date, makeup FROM " + records + " WHERE uuid = ?",
                rs -> new Object[]{rs.getString(1), rs.getInt(2)}, uuid.toString());
        for (Object[] r : rows) {
            try {
                d.dates.put(LocalDate.parse((String) r[0]), ((Integer) r[1]) != 0);
            } catch (Exception ignored) {}
        }
        return d;
    }

    public void put(PlayerData d) {
        cache.put(d.uuid, d);
    }

    /** 主线程调用：在缓存中记录签到（返回 false 表示已签过）。 */
    public boolean mark(PlayerData d, LocalDate date, boolean makeup) {
        return d.dates.putIfAbsent(date, makeup) == null;
    }

    /**
     * 异步线程调用：写入签到记录与汇总。先查询是否已存在（重复则跳过插入），
     * 其它数据库错误原样抛出，由调用方记录日志并保留“写入中”状态。
     */
    public synchronized void persist(UUID uuid, String name, LocalDate date, boolean makeup, int streak, int total, LocalDate last) throws SQLException {
        long now = System.currentTimeMillis();
        boolean exists = db.queryOne("SELECT 1 FROM " + records + " WHERE uuid = ? AND date = ?",
                rs -> Boolean.TRUE, uuid.toString(), date.toString()).isPresent();
        if (!exists) {
            db.update("INSERT INTO " + records + " (uuid, name, date, time, makeup) VALUES (?,?,?,?,?)",
                    uuid.toString(), name, date.toString(), now, makeup ? 1 : 0);
        }
        String up = db.upsert(players, List.of("uuid"), List.of("uuid", "name", "streak", "total", "last_date", "updated"));
        db.update(up, uuid.toString(), name, streak, total, last == null ? null : last.toString(), now);
    }

    /** 异步线程调用：清空玩家签到数据。 */
    public void reset(UUID uuid) throws SQLException {
        db.update("DELETE FROM " + records + " WHERE uuid = ?", uuid.toString());
        db.update("DELETE FROM " + players + " WHERE uuid = ?", uuid.toString());
    }

    /**
     * 排行榜。streak 排行只统计 last_date 不早于昨天的玩家（否则连续已中断）。
     */
    public List<Summary> top(boolean byStreak, LocalDate today, int offset, int limit) throws SQLException {
        String sql;
        Object[] params;
        if (byStreak) {
            sql = "SELECT uuid, name, streak, total, last_date FROM " + players
                    + " WHERE last_date >= ? ORDER BY streak DESC, total DESC LIMIT ? OFFSET ?";
            params = new Object[]{today.minusDays(1).toString(), limit, offset};
        } else {
            sql = "SELECT uuid, name, streak, total, last_date FROM " + players
                    + " ORDER BY total DESC, streak DESC LIMIT ? OFFSET ?";
            params = new Object[]{limit, offset};
        }
        String yesterday = today.minusDays(1).toString();
        return db.query(sql, rs -> {
            String last = rs.getString(5);
            int streak = last != null && last.compareTo(yesterday) >= 0 ? rs.getInt(3) : 0;
            return new Summary(UUID.fromString(rs.getString(1)), rs.getString(2), streak, rs.getInt(4), last);
        }, params);
    }

    public long countPlayers(boolean activeOnly, LocalDate today) throws SQLException {
        if (activeOnly) {
            return db.queryOne("SELECT COUNT(*) FROM " + players + " WHERE last_date >= ?", rs -> rs.getLong(1),
                    today.minusDays(1).toString()).orElse(0L);
        }
        return db.queryOne("SELECT COUNT(*) FROM " + players, rs -> rs.getLong(1)).orElse(0L);
    }

    public long countRecords(String fromDate, String toDate, Boolean makeup) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + records + " WHERE date >= ? AND date <= ?" + (makeup == null ? "" : " AND makeup = " + (makeup ? 1 : 0));
        return db.queryOne(sql, rs -> rs.getLong(1), fromDate, toDate).orElse(0L);
    }

    public List<Record> records(String keyword, int offset, int limit) throws SQLException {
        String where = keyword == null || keyword.isBlank() ? "" : " WHERE name LIKE ? OR uuid = ? OR date LIKE ?";
        Object[] params = where.isEmpty() ? new Object[]{limit, offset}
                : new Object[]{"%" + keyword + "%", keyword, keyword + "%", limit, offset};
        return db.query("SELECT id, time, uuid, name, date, makeup FROM " + records + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                rs -> new Record(rs.getLong(1), rs.getLong(2), UUID.fromString(rs.getString(3)), rs.getString(4),
                        rs.getString(5), rs.getInt(6) != 0), params);
    }

    public long countRecords(String keyword) throws SQLException {
        if (keyword == null || keyword.isBlank()) {
            return db.queryOne("SELECT COUNT(*) FROM " + records, rs -> rs.getLong(1)).orElse(0L);
        }
        return db.queryOne("SELECT COUNT(*) FROM " + records + " WHERE name LIKE ? OR uuid = ? OR date LIKE ?",
                rs -> rs.getLong(1), "%" + keyword + "%", keyword, keyword + "%").orElse(0L);
    }
}
