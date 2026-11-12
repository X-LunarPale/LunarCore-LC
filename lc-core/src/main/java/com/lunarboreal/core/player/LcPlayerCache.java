package com.lunarboreal.core.player;

import com.lunarboreal.api.player.PlayerCache;
import com.lunarboreal.core.LunarCorePlugin;
import com.lunarboreal.core.database.SqlDatabase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家档案缓存（表 lc_players）。启动时全量加载到内存；加入/退出时异步写回。
 * play_time 列为已结算时长，{@link #get} 返回值包含在线玩家当前会话时长。
 */
public final class LcPlayerCache implements PlayerCache, Listener {

    private static final List<String> COLUMNS = List.of("uuid", "name", "name_lower", "first_join", "last_join", "last_quit", "last_ip", "play_time");

    private final LunarCorePlugin plugin;
    private final SqlDatabase db;
    private final String table;
    /** 已结算记录（不含当前会话）。 */
    private final Map<UUID, PlayerRecord> records = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    /** 在线会话开始时间。 */
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();

    public LcPlayerCache(LunarCorePlugin plugin, SqlDatabase db) {
        this.plugin = plugin;
        this.db = db;
        this.table = db.table("players");
    }

    /** 建表并全量加载（启动时同步执行）。 */
    public void init() throws SQLException {
        db.update("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "name VARCHAR(32) NOT NULL,"
                + "name_lower VARCHAR(32) NOT NULL,"
                + "first_join BIGINT NOT NULL,"
                + "last_join BIGINT NOT NULL,"
                + "last_quit BIGINT NOT NULL DEFAULT 0,"
                + "last_ip VARCHAR(64),"
                + "play_time BIGINT NOT NULL DEFAULT 0)");
        db.createIndexQuietly(table + "_name", table, "name_lower");
        List<PlayerRecord> all = db.query("SELECT uuid, name, first_join, last_join, last_quit, last_ip, play_time FROM " + table,
                rs -> new PlayerRecord(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getString(6), rs.getLong(7)));
        for (PlayerRecord r : all) put(r);
        long now = System.currentTimeMillis();
        // /reload 或热加载时补上已在线玩家
        for (Player p : Bukkit.getOnlinePlayers()) handleJoin(p, now);
        plugin.getLogger().info("已加载 " + records.size() + " 条玩家档案");
    }

    private void put(PlayerRecord r) {
        PlayerRecord old = records.put(r.uuid(), r);
        if (old != null && !old.name().equalsIgnoreCase(r.name())) {
            byName.remove(old.name().toLowerCase(Locale.ROOT), r.uuid());
        }
        byName.put(r.name().toLowerCase(Locale.ROOT), r.uuid());
    }

    private PlayerRecord withSession(PlayerRecord r) {
        Long start = sessions.get(r.uuid());
        if (start == null) return r;
        return new PlayerRecord(r.uuid(), r.name(), r.firstJoin(), r.lastJoin(), r.lastQuit(), r.lastIp(),
                r.playTimeMillis() + Math.max(0, System.currentTimeMillis() - start));
    }

    @Override
    public Optional<PlayerRecord> get(UUID uuid) {
        if (uuid == null) return Optional.empty();
        PlayerRecord r = records.get(uuid);
        return r == null ? Optional.empty() : Optional.of(withSession(r));
    }

    @Override
    public Optional<PlayerRecord> getByName(String name) {
        if (name == null) return Optional.empty();
        UUID id = byName.get(name.toLowerCase(Locale.ROOT));
        return id == null ? Optional.empty() : get(id);
    }

    /** 全部记录（含当前会话时长）。 */
    public Collection<PlayerRecord> all() {
        List<PlayerRecord> out = new ArrayList<>(records.size());
        for (PlayerRecord r : records.values()) out.add(withSession(r));
        return out;
    }

    public int size() {
        return records.size();
    }

    public boolean isOnline(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        handleJoin(e.getPlayer(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        handleQuit(e.getPlayer().getUniqueId(), System.currentTimeMillis(), true);
    }

    private void handleJoin(Player p, long now) {
        String ip = null;
        InetSocketAddress addr = p.getAddress();
        if (addr != null && addr.getAddress() != null) ip = addr.getAddress().getHostAddress();
        PlayerRecord old = records.get(p.getUniqueId());
        PlayerRecord r = old == null
                ? new PlayerRecord(p.getUniqueId(), p.getName(), now, now, 0, ip, 0)
                : new PlayerRecord(p.getUniqueId(), p.getName(), old.firstJoin(), now, old.lastQuit(), ip == null ? old.lastIp() : ip, old.playTimeMillis());
        put(r);
        sessions.put(p.getUniqueId(), now);
        save(r, true);
    }

    private void handleQuit(UUID uuid, long now, boolean async) {
        Long start = sessions.remove(uuid);
        PlayerRecord old = records.get(uuid);
        if (old == null) return;
        long add = start == null ? 0 : Math.max(0, now - start);
        PlayerRecord r = new PlayerRecord(uuid, old.name(), old.firstJoin(), old.lastJoin(), now, old.lastIp(), old.playTimeMillis() + add);
        put(r);
        save(r, async);
    }

    private void save(PlayerRecord r, boolean async) {
        String sql = db.upsert(table, List.of("uuid"), COLUMNS);
        Object[] params = {r.uuid().toString(), r.name(), r.name().toLowerCase(Locale.ROOT), r.firstJoin(), r.lastJoin(),
                r.lastQuit(), r.lastIp(), r.playTimeMillis()};
        if (async) {
            db.updateAsync(sql, params);
        } else {
            try {
                db.update(sql, params);
            } catch (SQLException e) {
                plugin.getLogger().warning("保存玩家档案失败 " + r.name() + ": " + e.getMessage());
            }
        }
    }

    /** 周期性结算在线玩家时长并写回（异步）。 */
    public void checkpoint() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : sessions.entrySet()) {
            PlayerRecord old = records.get(e.getKey());
            if (old == null) continue;
            long add = Math.max(0, now - e.getValue());
            e.setValue(now);
            PlayerRecord r = new PlayerRecord(old.uuid(), old.name(), old.firstJoin(), old.lastJoin(), old.lastQuit(), old.lastIp(), old.playTimeMillis() + add);
            put(r);
            save(r, true);
        }
    }

    /** 插件关闭时同步结算所有在线玩家。 */
    public void shutdown() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(sessions.keySet())) handleQuit(id, now, false);
    }
}
