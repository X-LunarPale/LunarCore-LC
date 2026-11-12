package com.lunarboreal.antixray;

import com.lunarboreal.api.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import com.lunarboreal.api.message.MessageService;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 方案三：挖矿行为检测。
 * <ul>
 *   <li>稀有矿 / 普通石头比率：时间窗口内每条稀有矿脉对应的石头数过少；</li>
 *   <li>密集：时间窗口内挖到的稀有矿脉加权数量过多；</li>
 *   <li>直线命中：挖到的矿脉在被挖之前完全未暴露（周围的空气全部是该玩家自己挖开的），
 *       且玩家最近的挖掘路径持续逼近该矿脉。</li>
 * </ul>
 * 统计以"矿脉"为单位（与 2 秒内同种矿石相邻的方块视为同一矿脉），避免一次挖开大矿脉造成误报。
 */
final class MiningMonitor implements Listener {

    static final String COMMON_KEY = "COMMON";
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final AntiXrayModule m;
    private final Database db;
    final String minedTable;
    final String alertTable;

    // ---- 配置 ----
    private volatile boolean enabled = true;
    private volatile Map<Material, Double> rare = new EnumMap<>(Material.class);
    private volatile Set<Material> common = EnumSet.noneOf(Material.class);
    private volatile long windowMs = 20 * 60_000L;
    private volatile double denseThreshold = 8;
    private volatile int ratioMinVeins = 4;
    private volatile double ratioMaxStonePerVein = 25;
    private volatile boolean directEnabled = true;
    private volatile int directLookback = 12;
    private volatile double directMinStart = 6;
    private volatile double directMonotone = 0.85;
    private volatile int directThreshold = 3;
    private volatile long cooldownMs = 300_000L;
    private volatile int maxCheckY = 320;

    // ---- 状态 ----
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** 待写库增量：uuid → material → 数量。 */
    private final ConcurrentHashMap<UUID, Map<String, Long>> pending = new ConcurrentHashMap<>();
    /** 玩家放置的稀有矿位置（world:posKey），挖掉时不计入统计，避免"放置再挖"误报。 */
    private final Set<String> placedOres = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    static final class Session {
        final String name;
        final ArrayDeque<double[]> veins = new ArrayDeque<>();      // {time, weight}
        final ArrayDeque<long[]> stoneBuckets = new ArrayDeque<>();  // {minute, count}
        final ArrayDeque<long[]> recentBreaks = new ArrayDeque<>();  // {time, posKey}
        final ArrayDeque<long[]> recentOres = new ArrayDeque<>();    // {time, posKey, materialOrdinal}
        final ArrayDeque<Long> directHits = new ArrayDeque<>();
        final Map<String, Long> counts = new ConcurrentHashMap<>();
        final Map<String, Long> lastAlert = new HashMap<>();
        volatile int alerts;
        volatile long totalVeins;
        volatile long totalDirect;

        Session(String name) { this.name = name; }
    }

    record Alert(long id, UUID uuid, String name, String type, String detail, String world, int x, int y, int z, long created) {}

    MiningMonitor(AntiXrayModule m, Database db) {
        this.m = m;
        this.db = db;
        this.minedTable = db.table("antixray_mined");
        this.alertTable = db.table("antixray_alerts");
    }

    Database db() { return db; }

    void init() {
        db.async(d -> {
            d.update("CREATE TABLE IF NOT EXISTS " + minedTable + " (uuid VARCHAR(36) NOT NULL, name VARCHAR(32), "
                    + "material VARCHAR(64) NOT NULL, amount BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, material))");
            d.update("CREATE TABLE IF NOT EXISTS " + alertTable + " (id " + d.autoIncrementPk() + ", uuid VARCHAR(36), "
                    + "name VARCHAR(32), type VARCHAR(16), detail VARCHAR(255), world VARCHAR(64), x INT, y INT, z INT, created BIGINT)");
            for (String col : new String[]{"uuid", "created"}) {
                try { d.update("CREATE INDEX idx_" + alertTable + "_" + col + " ON " + alertTable + " (" + col + ")"); } catch (SQLException ignored) {}
            }
            return null;
        }).exceptionally(e -> { m.logger().log(Level.SEVERE, "初始化反矿透数据表失败", e); return null; });
    }

    void reload() {
        ConfigurationSection c = m.config().getConfigurationSection("detection");
        if (c == null) { enabled = false; return; }
        enabled = c.getBoolean("enabled", true);
        Map<Material, Double> r = new EnumMap<>(Material.class);
        ConfigurationSection rs = c.getConfigurationSection("rare-ores");
        if (rs != null) for (String k : rs.getKeys(false)) {
            Material mat = Material.matchMaterial(k);
            if (mat != null) r.put(mat, rs.getDouble(k, 1.0));
        }
        rare = r;
        Set<Material> cm = EnumSet.noneOf(Material.class);
        for (String s : c.getStringList("common-blocks")) {
            Material mat = Material.matchMaterial(s);
            if (mat != null) cm.add(mat);
        }
        common = cm;
        windowMs = Math.max(1, c.getLong("window-minutes", 20)) * 60_000L;
        denseThreshold = c.getDouble("dense.threshold", 8);
        ratioMinVeins = c.getInt("ratio.min-veins", 4);
        ratioMaxStonePerVein = c.getDouble("ratio.max-stone-per-vein", 25);
        directEnabled = c.getBoolean("direct.enabled", true);
        directLookback = Math.max(3, c.getInt("direct.lookback-blocks", 12));
        directMinStart = c.getDouble("direct.min-start-distance", 6);
        directMonotone = c.getDouble("direct.approach-ratio", 0.85);
        directThreshold = Math.max(1, c.getInt("direct.threshold", 3));
        cooldownMs = Math.max(0, c.getLong("alert-cooldown-seconds", 300)) * 1000L;
        maxCheckY = c.getInt("max-y", 320);
    }

    Map<Material, Double> rareOres() { return rare; }

    Session session(UUID uuid) { return sessions.get(uuid); }

    // ======================================================================
    // 事件
    // ======================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (p.hasPermission("lc.antixray.bypass")) return;
        Block b = e.getBlock();
        Material mat = b.getType();
        long now = System.currentTimeMillis();
        Session s = sessions.computeIfAbsent(p.getUniqueId(), k -> new Session(p.getName()));
        names.put(p.getUniqueId(), p.getName());
        long pos = FakeOreEngine.posKey(b.getX(), b.getY(), b.getZ());

        Double weight = rare.get(mat);
        if (weight == null) {
            if (common.contains(mat)) {
                addStone(s, now);
                increment(p.getUniqueId(), s, COMMON_KEY);
            }
            s.recentBreaks.addLast(new long[]{now, pos});
            while (s.recentBreaks.size() > 64) s.recentBreaks.pollFirst();
            return;
        }

        if (!placedOres.isEmpty() && placedOres.remove(b.getWorld().getName() + ":" + pos)) return; // 玩家放置的矿
        increment(p.getUniqueId(), s, mat.name());
        boolean newVein = isNewVein(s, b, mat, now);
        s.recentOres.addLast(new long[]{now, pos, mat.ordinal()});
        while (s.recentOres.size() > 64) s.recentOres.pollFirst();
        if (!newVein || b.getY() > maxCheckY) return;

        s.totalVeins++;
        s.veins.addLast(new double[]{now, weight});
        prune(s, now);

        double weighted = 0;
        for (double[] v : s.veins) weighted += v[1];
        int veinCount = s.veins.size();
        long stones = stoneCount(s);
        String oreName = mat.name().toLowerCase(Locale.ROOT);
        Location loc = b.getLocation();

        if (directEnabled && isDirectHit(s, b, now)) {
            s.totalDirect++;
            s.directHits.addLast(now);
            if (s.directHits.size() >= directThreshold) {
                alert(p, s, "DIRECT", m.raw("detail.direct", "count", s.directHits.size(),
                        "minutes", windowMs / 60_000L, "ore", oreName), loc);
            }
        }
        if (weighted >= denseThreshold) {
            alert(p, s, "DENSE", m.raw("detail.dense", "veins", veinCount, "weight", fmt(weighted),
                    "minutes", windowMs / 60_000L), loc);
        }
        if (veinCount >= ratioMinVeins && (double) stones / veinCount < ratioMaxStonePerVein) {
            alert(p, s, "RATIO", m.raw("detail.ratio", "veins", veinCount, "stone", stones,
                    "per", fmt((double) stones / veinCount), "minutes", windowMs / 60_000L), loc);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!enabled) return;
        Block b = e.getBlockPlaced();
        if (!rare.containsKey(b.getType())) return;
        if (placedOres.size() > 100_000) placedOres.clear(); // 简单上限，防止无限增长
        placedOres.add(b.getWorld().getName() + ":" + FakeOreEngine.posKey(b.getX(), b.getY(), b.getZ()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
        flushAsync();
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) { s.recentBreaks.clear(); s.recentOres.clear(); }
    }

    // ======================================================================
    // 检测逻辑
    // ======================================================================

    private boolean isNewVein(Session s, Block b, Material mat, long now) {
        int ord = mat.ordinal();
        for (long[] o : s.recentOres) {
            if (o[2] != ord || now - o[0] > 120_000L) continue;
            int x = FakeOreEngine.posX(o[1]), y = FakeOreEngine.posY(o[1]), z = FakeOreEngine.posZ(o[1]);
            if (Math.abs(x - b.getX()) <= 1 && Math.abs(y - b.getY()) <= 1 && Math.abs(z - b.getZ()) <= 1) return false;
        }
        return true;
    }

    /** 矿脉被挖之前是否完全未暴露（相邻的非实心方块都是玩家最近自己挖开的），且挖掘路径持续逼近。 */
    private boolean isDirectHit(Session s, Block ore, long now) {
        Set<Long> own = new HashSet<>();
        for (long[] r : s.recentBreaks) if (now - r[0] <= 300_000L) own.add(r[1]);
        boolean touchedOwnTunnel = false;
        for (BlockFace f : FACES) {
            Block nb = ore.getRelative(f);
            if (nb.getType().isOccluding()) continue;
            if (!own.contains(FakeOreEngine.posKey(nb.getX(), nb.getY(), nb.getZ()))) return false; // 天然暴露（洞穴等）
            touchedOwnTunnel = true;
        }
        if (!touchedOwnTunnel) return false;
        List<long[]> path = new ArrayList<>();
        for (long[] r : s.recentBreaks) if (now - r[0] <= 120_000L) path.add(r);
        if (path.size() < directLookback) return false;
        path = path.subList(path.size() - directLookback, path.size());
        double[] d = new double[path.size()];
        for (int i = 0; i < d.length; i++) {
            long k = path.get(i)[1];
            double dx = FakeOreEngine.posX(k) - ore.getX(), dy = FakeOreEngine.posY(k) - ore.getY(), dz = FakeOreEngine.posZ(k) - ore.getZ();
            d[i] = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        if (d[0] < directMinStart) return false;
        int approaching = 0;
        for (int i = 1; i < d.length; i++) if (d[i] <= d[i - 1] + 0.01) approaching++;
        return (double) approaching / (d.length - 1) >= directMonotone;
    }

    private void prune(Session s, long now) {
        while (!s.veins.isEmpty() && now - s.veins.peekFirst()[0] > windowMs) s.veins.pollFirst();
        while (!s.directHits.isEmpty() && now - s.directHits.peekFirst() > windowMs) s.directHits.pollFirst();
        long minMinute = (now - windowMs) / 60_000L;
        while (!s.stoneBuckets.isEmpty() && s.stoneBuckets.peekFirst()[0] < minMinute) s.stoneBuckets.pollFirst();
    }

    private static void addStone(Session s, long now) {
        long minute = now / 60_000L;
        long[] last = s.stoneBuckets.peekLast();
        if (last != null && last[0] == minute) last[1]++;
        else s.stoneBuckets.addLast(new long[]{minute, 1});
        while (s.stoneBuckets.size() > 24 * 60) s.stoneBuckets.pollFirst();
    }

    private static long stoneCount(Session s) {
        long n = 0;
        for (long[] b : s.stoneBuckets) n += b[1];
        return n;
    }

    /** 窗口内数据（命令/面板展示）。 */
    Map<String, Object> windowInfo(Session s) {
        long now = System.currentTimeMillis();
        prune(s, now);
        double weighted = 0;
        for (double[] v : s.veins) weighted += v[1];
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("veins", s.veins.size());
        o.put("weight", fmt(weighted));
        o.put("stone", stoneCount(s));
        o.put("direct", s.directHits.size());
        o.put("alerts", s.alerts);
        o.put("totalVeins", s.totalVeins);
        o.put("totalDirect", s.totalDirect);
        return o;
    }

    private void increment(UUID uuid, Session s, String key) {
        s.counts.merge(key, 1L, Long::sum);
        addPending(uuid, key, 1L);
    }

    /** 内层 map 只在 ConcurrentHashMap.compute 内修改；写库时用 remove 原子取出整份，不会丢增量。 */
    private void addPending(UUID uuid, String key, long amount) {
        pending.compute(uuid, (k, v) -> {
            Map<String, Long> mp = v == null ? new HashMap<>() : v;
            mp.merge(key, amount, Long::sum);
            return mp;
        });
    }

    static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    // ======================================================================
    // 告警
    // ======================================================================

    private void alert(Player p, Session s, String type, String detail, Location loc) {
        long now = System.currentTimeMillis();
        Long last = s.lastAlert.get(type);
        if (last != null && now - last < cooldownMs) return;
        s.lastAlert.put(type, now);
        s.alerts++;
        UUID uuid = p.getUniqueId();
        String world = loc.getWorld() == null ? "?" : loc.getWorld().getName();
        String typeLabel = m.raw("types." + type.toLowerCase(Locale.ROOT));
        // typeLabel / detail 是本模块语言文件生成的可信文本，作为富文本插入（避免被 core 转义显示原始标签）
        Object[] args = {"player", p.getName(), "type", MessageService.rich(typeLabel), "detail", MessageService.rich(detail), "world", world,
                "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ()};
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("lc.antixray.notify")) m.msg(staff, "alert.notify", args);
        }
        m.msg(Bukkit.getConsoleSender(), "alert.notify", args);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uuid", uuid.toString());
        payload.put("name", p.getName());
        payload.put("type", type);
        payload.put("typeLabel", typeLabel);
        payload.put("detail", detail);
        payload.put("world", world);
        payload.put("x", loc.getBlockX());
        payload.put("y", loc.getBlockY());
        payload.put("z", loc.getBlockZ());
        payload.put("created", now);
        try { m.api().web().broadcast("antixray.alert", payload); } catch (Exception ignored) {}

        db.async(d -> d.insert("INSERT INTO " + alertTable + " (uuid, name, type, detail, world, x, y, z, created) VALUES (?,?,?,?,?,?,?,?,?)",
                        uuid.toString(), p.getName(), type, detail.length() > 255 ? detail.substring(0, 255) : detail,
                        world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), now))
                .exceptionally(e -> { m.logger().log(Level.WARNING, "写入告警失败", e); return null; });

        runActions(p, s, type, typeLabel, detail);
    }

    private void runActions(Player p, Session s, String type, String typeLabel, String detail) {
        ConfigurationSection a = m.config().getConfigurationSection("actions");
        if (a == null || !a.getBoolean("enabled", false)) return;
        List<String> types = a.getStringList("types");
        if (!types.isEmpty() && types.stream().noneMatch(t -> t.equalsIgnoreCase(type))) return;
        if (s.alerts < a.getInt("min-alerts", 1)) return;
        for (String cmd : a.getStringList("commands")) {
            String c = cmd.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString())
                    .replace("{type}", typeLabel).replace("{detail}", detail).replace("{alerts}", String.valueOf(s.alerts));
            if (c.startsWith("/")) c = c.substring(1);
            final String fc = c;
            m.runTask(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fc));
        }
        m.audit("antixray", "auto-action", p.getName() + " " + type + " → " + a.getStringList("commands"));
    }

    // ======================================================================
    // 持久化
    // ======================================================================

    private Map<UUID, Map<String, Long>> swapPending() {
        Map<UUID, Map<String, Long>> out = new HashMap<>();
        for (UUID u : new ArrayList<>(pending.keySet())) {
            Map<String, Long> mp = pending.remove(u);
            if (mp != null && !mp.isEmpty()) out.put(u, mp);
        }
        return out;
    }

    private Integer writeDeltas(Database d, Map<UUID, Map<String, Long>> deltas) throws SQLException {
        int n = 0;
        for (Map.Entry<UUID, Map<String, Long>> e : deltas.entrySet()) {
            String u = e.getKey().toString();
            String name = names.getOrDefault(e.getKey(), null);
            for (Map.Entry<String, Long> c : e.getValue().entrySet()) {
                int rows = d.update("UPDATE " + minedTable + " SET amount = amount + ?, name = COALESCE(?, name) WHERE uuid = ? AND material = ?",
                        c.getValue(), name, u, c.getKey());
                if (rows == 0) {
                    d.update("INSERT INTO " + minedTable + " (uuid, name, material, amount) VALUES (?,?,?,?)", u, name, c.getKey(), c.getValue());
                }
                n++;
            }
        }
        return n;
    }

    CompletableFuture<Integer> flushAsync() {
        Map<UUID, Map<String, Long>> deltas = swapPending();
        if (deltas.isEmpty()) return CompletableFuture.completedFuture(0);
        return db.async(d -> writeDeltas(d, deltas)).whenComplete((n, err) -> {
            if (err != null) {
                m.logger().log(Level.WARNING, "写入挖矿统计失败，将在下次重试", err);
                deltas.forEach((u, map) -> map.forEach((k, v) -> addPending(u, k, v)));
            }
        });
    }

    /** 模块禁用时调用：等待写回完成（最多 5 秒）。 */
    void flushBlocking() {
        try {
            flushAsync().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            m.logger().log(Level.WARNING, "禁用时写回挖矿统计失败", e);
        }
    }

    /** 待写库增量（用于合并到查询结果）。 */
    Map<String, Long> pendingFor(UUID uuid) {
        Map<String, Long>[] copy = new Map[1];
        pending.computeIfPresent(uuid, (k, v) -> { copy[0] = Map.copyOf(v); return v; });
        return copy[0] == null ? Map.of() : copy[0];
    }

    Map<UUID, Map<String, Long>> pendingAll() {
        Map<UUID, Map<String, Long>> out = new HashMap<>();
        for (UUID u : new ArrayList<>(pending.keySet())) {
            Map<String, Long> c = pendingFor(u);
            if (!c.isEmpty()) out.put(u, c);
        }
        return out;
    }

    String nameOf(UUID uuid) {
        String n = names.get(uuid);
        return n != null ? n : m.api().players().nameOf(uuid);
    }

    /** 查询某玩家累计挖矿数量（阻塞，异步线程调用）。 */
    Map<String, Long> totalsSync(UUID uuid) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : db.query("SELECT material, amount FROM " + minedTable + " WHERE uuid = ?",
                rs -> new Object[]{rs.getString("material"), rs.getLong("amount")}, uuid.toString())) {
            out.put((String) row[0], (Long) row[1]);
        }
        pendingFor(uuid).forEach((k, v) -> out.merge(k, v, Long::sum));
        return out;
    }

    List<Alert> alertsSync(UUID uuid, int offset, int limit) throws SQLException {
        String where = uuid == null ? "" : " WHERE uuid = ?";
        Object[] params = uuid == null ? new Object[]{limit, offset} : new Object[]{uuid.toString(), limit, offset};
        return db.query("SELECT * FROM " + alertTable + where + " ORDER BY id DESC LIMIT ? OFFSET ?", rs -> {
            String u = rs.getString("uuid");
            return new Alert(rs.getLong("id"), u == null ? null : UUID.fromString(u), rs.getString("name"), rs.getString("type"),
                    rs.getString("detail"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getLong("created"));
        }, params);
    }

    /**
     * 可疑玩家排行（阻塞）。score = 告警数×20 + min(每千石头稀有矿加权数, 500)/5。
     */
    List<Map<String, Object>> suspectsSync() throws SQLException {
        Map<UUID, double[]> agg = new HashMap<>(); // {rareWeighted, rareBlocks, common}
        Map<UUID, String> nm = new HashMap<>();
        Map<Material, Double> rareMap = rare;
        db.query("SELECT uuid, name, material, amount FROM " + minedTable, rs -> {
            UUID u = UUID.fromString(rs.getString("uuid"));
            String n = rs.getString("name");
            if (n != null) nm.put(u, n);
            accumulate(agg, rareMap, u, rs.getString("material"), rs.getLong("amount"));
            return null;
        });
        pendingAll().forEach((u, mp) -> mp.forEach((k, v) -> accumulate(agg, rareMap, u, k, v)));
        Map<UUID, long[]> alerts = new HashMap<>(); // {count, last}
        db.query("SELECT uuid, COUNT(*) AS c, MAX(created) AS l FROM " + alertTable + " GROUP BY uuid", rs -> {
            String u = rs.getString("uuid");
            if (u != null) alerts.put(UUID.fromString(u), new long[]{rs.getLong("c"), rs.getLong("l")});
            return null;
        });
        Set<UUID> all = new HashSet<>(agg.keySet());
        all.addAll(alerts.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID u : all) {
            double[] a = agg.getOrDefault(u, new double[3]);
            long[] al = alerts.getOrDefault(u, new long[2]);
            double perK = a[2] <= 0 ? (a[0] > 0 ? a[0] * 1000 : 0) : a[0] * 1000 / a[2];
            double score = al[0] * 20 + Math.min(perK, 500) / 5;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", u.toString());
            row.put("name", nm.getOrDefault(u, nameOf(u)));
            row.put("rare", (long) a[1]);
            row.put("rareWeighted", fmt(a[0]));
            row.put("stone", (long) a[2]);
            row.put("perThousand", fmt(perK));
            row.put("alerts", al[0]);
            row.put("lastAlert", al[1] == 0 ? null : al[1]);
            row.put("score", Math.round(score * 10) / 10.0);
            row.put("online", Bukkit.getPlayer(u) != null);
            out.add(row);
        }
        out.sort((x, y) -> Double.compare((Double) y.get("score"), (Double) x.get("score")));
        return out;
    }

    private static void accumulate(Map<UUID, double[]> agg, Map<Material, Double> rareMap, UUID u, String material, long amount) {
        double[] a = agg.computeIfAbsent(u, k -> new double[3]);
        if (COMMON_KEY.equals(material)) { a[2] += amount; return; }
        Material mat = Material.matchMaterial(material);
        Double w = mat == null ? null : rareMap.get(mat);
        a[1] += amount;
        a[0] += amount * (w == null ? 1.0 : w);
    }
}
