package com.lunarboreal.teleport;

import com.lunarboreal.api.database.Database;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 家管理：在线玩家内存缓存，写入异步落库。 */
public final class HomeManager {

    public record Home(UUID owner, String name, StoredLoc loc, long created) {}

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1,32}");
    private static final String PREFIX = "lc.teleport.homes.";

    private final TeleportModule m;
    private final Map<UUID, Map<String, Home>> cache = new ConcurrentHashMap<>();
    private String table;

    public HomeManager(TeleportModule m) {
        this.m = m;
        this.table = m.db().table("teleport_homes");
    }

    public void createTable() throws java.sql.SQLException {
        Database db = m.db();
        db.update("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "uuid VARCHAR(36) NOT NULL, name VARCHAR(64) NOT NULL, world VARCHAR(64) NOT NULL, "
                + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, "
                + "created_at BIGINT NOT NULL, PRIMARY KEY (uuid, name))");
    }

    public String table() {
        return table;
    }

    private static Home map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Home(UUID.fromString(rs.getString("uuid")), rs.getString("name"), StoredLoc.read(rs), rs.getLong("created_at"));
    }

    /** 异步读取某玩家的全部家（不走缓存）。 */
    public CompletableFuture<Map<String, Home>> fetch(UUID uuid) {
        return m.db().queryAsync("SELECT * FROM " + table + " WHERE uuid = ? ORDER BY created_at", HomeManager::map, uuid.toString())
                .thenApply(list -> {
                    Map<String, Home> out = Collections.synchronizedMap(new LinkedHashMap<>());
                    for (Home h : list) out.put(h.name().toLowerCase(Locale.ROOT), h);
                    return out;
                });
    }

    /** 玩家加入时加载到缓存。 */
    public void load(UUID uuid) {
        fetch(uuid).thenAccept(map -> { if (org.bukkit.Bukkit.getPlayer(uuid) != null) cache.put(uuid, map); })
                .exceptionally(e -> { m.logger().warning("加载家失败 " + uuid + ": " + e.getMessage()); return null; });
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    public boolean isLoaded(UUID uuid) {
        return cache.containsKey(uuid);
    }

    /** 缓存优先，否则异步查询。 */
    public CompletableFuture<Map<String, Home>> homes(UUID uuid) {
        Map<String, Home> c = cache.get(uuid);
        return c != null ? CompletableFuture.completedFuture(c) : fetch(uuid);
    }

    /** 仅缓存（在线玩家），未加载返回空。 */
    public Map<String, Home> cached(UUID uuid) {
        Map<String, Home> c = cache.get(uuid);
        return c == null ? Collections.emptyMap() : c;
    }

    public void set(UUID uuid, String name, StoredLoc loc) {
        Home h = new Home(uuid, name, loc, System.currentTimeMillis());
        Map<String, Home> c = cache.get(uuid);
        if (c != null) c.put(name.toLowerCase(Locale.ROOT), h);
        String upsert = m.db().upsert(table, List.of("uuid", "name"),
                List.of("uuid", "name", "world", "x", "y", "z", "yaw", "pitch", "created_at"));
        // 家名忽略大小写：先删除同名不同大小写的旧行，避免 SQLite 区分大小写主键产生重复数据
        m.db().async(d -> {
            d.update("DELETE FROM " + table + " WHERE uuid = ? AND LOWER(name) = ? AND name <> ?",
                    uuid.toString(), name.toLowerCase(Locale.ROOT), name);
            return d.update(upsert, uuid.toString(), name, loc.world(), loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch(), h.created());
        }).exceptionally(e -> { m.logger().warning("保存家失败: " + e.getMessage()); return 0; });
    }

    public CompletableFuture<Integer> delete(UUID uuid, String name) {
        Map<String, Home> c = cache.get(uuid);
        if (c != null) c.remove(name.toLowerCase(Locale.ROOT));
        return m.db().updateAsync("DELETE FROM " + table + " WHERE uuid = ? AND LOWER(name) = ?", uuid.toString(),
                name.toLowerCase(Locale.ROOT));
    }

    /** 家数量上限，-1 为无限。 */
    public int limit(Player p) {
        if (p.hasPermission(PREFIX + "unlimited")) return -1;
        int max = Math.max(0, m.config().getInt("homes.default-limit", 3));
        var groups = m.config().getConfigurationSection("homes.groups");
        if (groups != null) {
            for (String g : groups.getKeys(false)) {
                if (p.hasPermission(PREFIX + g)) max = Math.max(max, groups.getInt(g));
            }
        }
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission().toLowerCase(Locale.ROOT);
            if (!perm.startsWith(PREFIX)) continue;
            String rest = perm.substring(PREFIX.length());
            if (rest.equals("unlimited") || rest.equals("*")) return -1;
            try {
                max = Math.max(max, Integer.parseInt(rest));
            } catch (NumberFormatException ignored) {
            }
        }
        return max;
    }

    public void clear() {
        cache.clear();
    }
}
