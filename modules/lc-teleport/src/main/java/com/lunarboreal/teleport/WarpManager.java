package com.lunarboreal.teleport;

import com.lunarboreal.api.database.Database;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;

/** 地标管理：全部缓存于内存，写入异步落库。 */
public final class WarpManager {

    /** restricted：需要 lc.teleport.warp.&lt;名&gt; 权限；cost &lt;= 0 时使用 costs.warp。 */
    public record Warp(String name, StoredLoc loc, boolean restricted, double cost, String creator, long created,
                       String icon, String description) {

        public Warp withLoc(StoredLoc l, String creator, long created) {
            return new Warp(name, l, restricted, cost, creator, created, icon, description);
        }

        public Warp withAccess(boolean restricted, double cost) {
            return new Warp(name, loc, restricted, cost, creator, created, icon, description);
        }

        public Warp withIcon(String icon) {
            return new Warp(name, loc, restricted, cost, creator, created, icon, description);
        }

        public Warp withDescription(String description) {
            return new Warp(name, loc, restricted, cost, creator, created, icon, description);
        }

        public String permission() {
            return "lc.teleport.warp." + name.toLowerCase(Locale.ROOT);
        }
    }

    private final TeleportModule m;
    private final Map<String, Warp> warps = new ConcurrentSkipListMap<>();
    private String table;

    public WarpManager(TeleportModule m) {
        this.m = m;
        this.table = m.db().table("teleport_warps");
    }

    public void createTable() throws java.sql.SQLException {
        Database db = m.db();
        db.update("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "name VARCHAR(64) NOT NULL PRIMARY KEY, world VARCHAR(64) NOT NULL, "
                + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, "
                + "restricted INT NOT NULL DEFAULT 0, cost DOUBLE NOT NULL DEFAULT 0, creator VARCHAR(64), created_at BIGINT NOT NULL, "
                + "icon VARCHAR(64), description VARCHAR(255))");
        // 旧版本表补列（已存在时忽略错误）
        for (String col : new String[]{"icon VARCHAR(64)", "description VARCHAR(255)"}) {
            try {
                db.update("ALTER TABLE " + table + " ADD COLUMN " + col);
            } catch (java.sql.SQLException ignored) {
            }
        }
    }

    public void loadAll() {
        m.db().queryAsync("SELECT * FROM " + table, rs -> new Warp(rs.getString("name"), StoredLoc.read(rs),
                        rs.getInt("restricted") != 0, rs.getDouble("cost"), rs.getString("creator"), rs.getLong("created_at"),
                        rs.getString("icon"), rs.getString("description")))
                .thenAccept(list -> {
                    warps.clear();
                    for (Warp w : list) warps.put(w.name().toLowerCase(Locale.ROOT), w);
                })
                .exceptionally(e -> { m.logger().warning("加载地标失败: " + e.getMessage()); return null; });
    }

    public Warp get(String name) {
        return name == null ? null : warps.get(name.toLowerCase(Locale.ROOT));
    }

    public List<Warp> all() {
        return new ArrayList<>(warps.values());
    }

    public int count() {
        return warps.size();
    }

    /** 可访问：全局开关或单独标记需要权限时检查 lc.teleport.warp.&lt;名&gt;。 */
    public boolean canUse(CommandSender s, Warp w) {
        boolean need = w.restricted() || m.config().getBoolean("warps.per-warp-permission", false);
        return !need || s.hasPermission(w.permission()) || s.hasPermission("lc.teleport.warp.*");
    }

    public CompletableFuture<Integer> save(Warp w) {
        warps.put(w.name().toLowerCase(Locale.ROOT), w);
        Database db = m.db();
        StoredLoc l = w.loc();
        return db.updateAsync(db.upsert(table, List.of("name"),
                        List.of("name", "world", "x", "y", "z", "yaw", "pitch", "restricted", "cost", "creator", "created_at",
                                "icon", "description")),
                w.name(), l.world(), l.x(), l.y(), l.z(), l.yaw(), l.pitch(), w.restricted() ? 1 : 0, w.cost(),
                w.creator() == null ? "" : w.creator(), w.created(),
                w.icon() == null ? "" : w.icon(), w.description() == null ? "" : w.description());
    }

    public CompletableFuture<Integer> delete(String name) {
        Warp w = warps.remove(name.toLowerCase(Locale.ROOT));
        if (w == null) return CompletableFuture.completedFuture(0);
        return m.db().updateAsync("DELETE FROM " + table + " WHERE name = ?", w.name());
    }
}
