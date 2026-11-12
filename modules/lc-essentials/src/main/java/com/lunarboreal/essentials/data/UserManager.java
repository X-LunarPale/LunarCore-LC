package com.lunarboreal.essentials.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lunarboreal.api.database.Database;
import com.lunarboreal.essentials.EssentialsModule;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家数据缓存：AsyncPlayerPreLoginEvent（异步线程）中加载，退出时异步保存，
 * 定时保存脏数据，模块禁用时同步写回全部。
 */
public final class UserManager {

    private final EssentialsModule m;
    private final Gson gson = new GsonBuilder().create();
    private final Map<UUID, UserData> cache = new ConcurrentHashMap<>();
    private String table;
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LunarCore-essentials-users");
        t.setDaemon(true);
        return t;
    });

    public UserManager(EssentialsModule m) {
        this.m = m;
    }

    public void init() throws SQLException {
        Database db = m.api().database();
        table = db.table("essentials_users");
        db.update("CREATE TABLE IF NOT EXISTS " + table + " (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32), data TEXT, updated BIGINT)");
    }

    /** 阻塞加载（仅在异步线程调用）。 */
    public UserData loadBlocking(UUID uuid) {
        UserData existing = cache.get(uuid);
        if (existing != null && existing.loaded) return existing;
        UserData data = null;
        boolean failed = false;
        try {
            Optional<String> json = m.api().database().queryOne("SELECT data FROM " + table + " WHERE uuid = ?",
                    rs -> rs.getString(1), uuid.toString());
            if (json.isPresent() && json.get() != null && !json.get().isEmpty()) {
                data = gson.fromJson(json.get(), UserData.class);
            }
        } catch (Exception e) {
            failed = true;
            m.logger().warning("加载玩家数据失败 " + uuid + ": " + e.getMessage());
        }
        if (data == null) data = new UserData();
        data.uuid = uuid;
        data.normalize();
        data.loaded = !failed;
        final UserData loadedData = data;
        // 若期间仅有占位数据则替换；已有真实数据则保留
        return cache.compute(uuid, (k, cur) -> cur != null && cur.loaded ? cur : loadedData);
    }

    /** 获取缓存（在线玩家必然存在；不存在时创建空数据，标记为需要异步补载）。 */
    public UserData get(UUID uuid) {
        return cache.computeIfAbsent(uuid, u -> {
            UserData d = new UserData();
            d.uuid = u;
            return d;
        });
    }

    public boolean isLoaded(UUID uuid) {
        UserData d = cache.get(uuid);
        return d != null && d.loaded;
    }

    public Map<UUID, UserData> all() {
        return cache;
    }

    /** 主线程快照：在调用线程（主线程）序列化，避免异步线程遍历正在被修改的集合。 */
    private record Snapshot(UserData data, String name, String json) {}

    private Snapshot snapshot(UserData d, String name) {
        if (!d.loaded) return null; // 未成功加载的数据不写回，避免覆盖
        d.dirty = false;
        return new Snapshot(d, name, gson.toJson(d));
    }

    private void write(Snapshot s) {
        try {
            Database db = m.api().database();
            String sql = db.upsert(table, List.of("uuid"), List.of("uuid", "name", "data", "updated"));
            db.update(sql, s.data.uuid.toString(), s.name, s.json, System.currentTimeMillis());
        } catch (Exception e) {
            s.data.dirty = true;
            m.logger().warning("保存玩家数据失败 " + s.data.uuid + ": " + e.getMessage());
        }
    }

    /** 单线程写入，保证同一玩家的多次保存按顺序落库。 */
    private void submit(Runnable r) {
        try {
            writer.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            r.run();
        }
    }

    /**
     * 异步保存（须在主线程调用）。unload=true 时等写库完成后才移出缓存（期间若玩家重进则保留），
     * 避免快速重进时读到旧数据。
     */
    public void saveAsync(UUID uuid, String name, boolean unload) {
        UserData d = cache.get(uuid);
        if (d == null) return;
        Snapshot s = snapshot(d, name);
        submit(() -> {
            if (s != null) write(s);
            if (unload) {
                try {
                    m.runTask(() -> {
                        if (org.bukkit.Bukkit.getPlayer(uuid) == null) cache.remove(uuid, d);
                    });
                } catch (Exception ignored) {
                    // 模块已禁用
                }
            }
        });
    }

    /** 定时任务（主线程）：快照脏数据后异步写入。 */
    public void flushDirtyAsync() {
        List<Snapshot> list = new ArrayList<>();
        for (UserData d : cache.values()) {
            if (!d.dirty) continue;
            Snapshot s = snapshot(d, m.api().players().nameOf(d.uuid));
            if (s != null) list.add(s);
        }
        if (list.isEmpty()) return;
        submit(() -> list.forEach(this::write));
    }

    /** 模块禁用时同步写回（等待挂起的写入完成）。 */
    public void saveAllBlocking() {
        writer.shutdown();
        try {
            writer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (UserData d : cache.values()) {
            Snapshot s = snapshot(d, m.api().players().nameOf(d.uuid));
            if (s != null) write(s);
        }
        cache.clear();
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }
}
