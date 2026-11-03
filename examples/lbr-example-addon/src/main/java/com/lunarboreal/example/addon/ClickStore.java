package com.lunarboreal.example.addon;

import com.lunarboreal.api.database.Database;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ClickService 实现，演示数据库约定：
 * <ul>
 *   <li>表名用 db.table("example_clicks") → lbr_example_clicks；</li>
 *   <li>建表 CREATE TABLE IF NOT EXISTS，SQL 兼容 SQLite / MySQL；</li>
 *   <li>热点数据放内存，写库全部走 db.updateAsync / async，主线程不阻塞。</li>
 * </ul>
 */
public final class ClickStore implements ClickService {

    private final Database db;
    private final Logger log;
    private final String table;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public ClickStore(Database db, Logger log) {
        this.db = db;
        this.log = log;
        this.table = db.table("example_clicks");
    }

    /** 异步建表并加载。 */
    public void initAsync() {
        db.async(d -> {
            d.update("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(32),"
                    + "points BIGINT NOT NULL DEFAULT 0,"
                    + "updated BIGINT NOT NULL)");
            List<Entry> all = d.query("SELECT uuid, name, points FROM " + table,
                    rs -> new Entry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
            for (Entry e : all) cache.putIfAbsent(e.uuid(), e);
            return all.size();
        }).whenComplete((n, err) -> {
            if (err != null) log.warning("加载示例数据失败: " + err.getMessage());
            else log.info("已加载 " + n + " 条示例数据");
        });
    }

    private void save(Entry e) {
        String sql = db.upsert(table, List.of("uuid"), List.of("uuid", "name", "points", "updated"));
        db.updateAsync(sql, e.uuid().toString(), e.name(), e.points(), System.currentTimeMillis())
                .exceptionally(t -> { log.warning("保存失败: " + t.getMessage()); return 0; });
    }

    @Override
    public long points(UUID player) {
        Entry e = cache.get(player);
        return e == null ? 0 : e.points();
    }

    @Override
    public long add(UUID player, String name, long delta) {
        Entry e = cache.compute(player, (k, old) -> new Entry(k,
                name != null ? name : old == null ? k.toString() : old.name(),
                Math.max(0, (old == null ? 0 : old.points()) + delta)));
        save(e);
        return e.points();
    }

    @Override
    public void reset(UUID player) {
        cache.remove(player);
        db.updateAsync("DELETE FROM " + table + " WHERE uuid = ?", player.toString());
    }

    @Override
    public List<Entry> top(int limit) {
        List<Entry> list = new ArrayList<>(cache.values());
        list.sort(Comparator.comparingLong(Entry::points).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public List<Entry> all() {
        return new ArrayList<>(cache.values());
    }

    public long sum() {
        long s = 0;
        for (Entry e : cache.values()) s += e.points();
        return s;
    }
}
