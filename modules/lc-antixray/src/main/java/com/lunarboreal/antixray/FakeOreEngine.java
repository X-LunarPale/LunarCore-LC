package com.lunarboreal.antixray;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 方案二：自研轻量"假矿"——把玩家附近<b>未暴露</b>（六面都是不透明方块）的矿石，
 * 通过 {@link Player#sendBlockChange} 对该玩家伪装成石头 / 深板岩 / 下界岩；
 * 当矿石因相邻方块被破坏、爆炸、活塞、液体流动而暴露时，再向附近玩家发送真实方块。
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>Paper：监听 PlayerChunkLoadEvent（区块数据发送给客户端时）立即排队；
 *       非 Paper：每 10 tick 扫描玩家视距内尚未伪装的区块（增量，只处理新进入的区块）。</li>
 *   <li>主线程按预算获取 ChunkSnapshot（中心 + 四邻区块，用于判断边界矿石），异步线程计算需要隐藏的矿石列表并缓存。</li>
 *   <li>主线程按每玩家每 tick 的发包预算 sendBlockChange。</li>
 * </ol>
 *
 * <h3>局限（务必知晓）</h3>
 * <ul>
 *   <li>区块原始数据先于伪装包到达客户端，在"区块包 → 伪装包"之间的短暂窗口（通常 1~数 tick）内真实矿石可见；
 *       会在收包瞬间缓存区块数据的作弊客户端不受影响。真正的防护请使用方案一（Paper 内置 anti-xray，在发包前混淆）。</li>
 *   <li>每个方块一个包，矿石多的区块会产生较多网络包（受 packets-per-tick 限速）。</li>
 *   <li>只处理配置的矿石与 max-y 以下；放置方块导致的"重新被包围"不会再次伪装。</li>
 *   <li>非 Paper 环境依靠视距估算客户端已加载区块，可能有少量遗漏/重复。</li>
 * </ul>
 */
final class FakeOreEngine implements Listener {

    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final AntiXrayModule m;

    // ---- 配置 ----
    private volatile Set<Material> ores = EnumSet.noneOf(Material.class);
    private volatile Set<String> worlds = Set.of();
    private volatile int maxY = 64;
    private volatile int radius = 0;
    private volatile int snapshotsPerTick = 2;
    private volatile int packetsPerTick = 256;
    private volatile int revealDistance = 160;
    private boolean paperEvents;
    private volatile boolean enabled;

    // ---- 状态 ----
    /** 主线程独占。 */
    private final Map<UUID, PlayerState> players = new HashMap<>();
    /** world → chunkKey → 隐藏矿石（posKey → 伪装数据）。 */
    private final Map<String, Map<Long, Map<Long, BlockData>>> cache = new ConcurrentHashMap<>();
    private final Set<String> computing = ConcurrentHashMap.newKeySet();
    /** 在途计算的区块版本号（主线程独占）：计算期间区块变化则递增，结果回主线程时比对。 */
    private final Map<String, Integer> versions = new HashMap<>();
    private final AtomicLong hiddenTotal = new AtomicLong();
    private BukkitTask task;
    private int tick;

    private final BlockData stone = Material.STONE.createBlockData();
    private final BlockData deepslate = Material.DEEPSLATE.createBlockData();
    private final BlockData netherrack = Material.NETHERRACK.createBlockData();

    private static final class PlayerState {
        String world;
        final Set<Long> masked = new HashSet<>();
        final ArrayDeque<Long> pending = new ArrayDeque<>();
        final Set<Long> pendingSet = new HashSet<>();
        final ArrayDeque<long[]> sends = new ArrayDeque<>(); // [posKey]
        final ArrayDeque<BlockData> sendData = new ArrayDeque<>();
    }

    FakeOreEngine(AntiXrayModule m) {
        this.m = m;
    }

    // ======================================================================

    void start(boolean paper) {
        this.paperEvents = paper;
        reload();
        task = m.runTaskTimer(this::tick, 20L, 2L);
    }

    void stop() {
        if (task != null && !task.isCancelled()) task.cancel();
        task = null;
        players.clear();
        cache.clear();
        computing.clear();
        versions.clear();
    }

    void reload() {
        ConfigurationSection c = m.config().getConfigurationSection("fake-ore");
        cache.clear();
        players.clear();
        // 在途计算结果全部作废
        for (String ck : computing) versions.merge(ck, 1, Integer::sum);
        hiddenTotal.set(0);
        enabled = c != null && c.getBoolean("enabled", false);
        if (c == null) return;
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String s : c.getStringList("ores")) {
            Material mat = Material.matchMaterial(s);
            if (mat != null && mat.isBlock()) set.add(mat);
        }
        ores = set;
        Set<String> ws = new HashSet<>();
        for (String s : c.getStringList("worlds")) ws.add(s.toLowerCase(Locale.ROOT));
        worlds = ws;
        maxY = c.getInt("max-y", 64);
        radius = Math.max(0, c.getInt("radius-chunks", 0));
        snapshotsPerTick = Math.max(1, c.getInt("chunks-per-tick", 2));
        packetsPerTick = Math.max(16, c.getInt("packets-per-tick", 256));
        revealDistance = Math.max(32, c.getInt("reveal-distance", 160));
    }

    long hiddenCount() { return hiddenTotal.get(); }

    int cachedChunks() { return cache.values().stream().mapToInt(Map::size).sum(); }

    private boolean enabledFor(Player p) {
        if (p.hasPermission("lc.antixray.bypass")) return false;
        Set<String> ws = worlds;
        return ws.isEmpty() || ws.contains(p.getWorld().getName().toLowerCase(Locale.ROOT));
    }

    private int viewRadius(Player p) {
        if (radius > 0) return radius;
        int vd;
        try { vd = p.getWorld().getSendViewDistance(); } catch (Throwable t) { vd = Bukkit.getViewDistance(); }
        return Math.min(Math.max(2, vd), 16);
    }

    private PlayerState state(Player p) {
        PlayerState st = players.computeIfAbsent(p.getUniqueId(), k -> new PlayerState());
        String w = p.getWorld().getName();
        if (!w.equals(st.world)) {
            st.world = w;
            st.masked.clear();
            st.pending.clear();
            st.pendingSet.clear();
            st.sends.clear();
            st.sendData.clear();
        }
        return st;
    }

    // ======================================================================
    // 主循环（主线程）
    // ======================================================================

    boolean isEnabled() { return enabled; }

    org.bukkit.plugin.Plugin plugin() { return m.plugin(); }

    private void tick() {
        if (!enabled) return;
        tick++;
        int budget = snapshotsPerTick;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!enabledFor(p)) { players.remove(p.getUniqueId()); continue; }
            PlayerState st = state(p);
            if (!paperEvents && tick % 5 == 0) scan(p, st);
            budget = processPending(p, st, budget);
            flushSends(p, st);
        }
    }

    /** 非 Paper：增量扫描视距内新进入的区块；丢弃已远离（客户端已卸载）的区块标记。 */
    private void scan(Player p, PlayerState st) {
        int r = viewRadius(p);
        int pcx = p.getLocation().getBlockX() >> 4;
        int pcz = p.getLocation().getBlockZ() >> 4;
        st.masked.removeIf(k -> Math.max(Math.abs(keyX(k) - pcx), Math.abs(keyZ(k) - pcz)) > r + 1);
        for (int ring = 0; ring <= r; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    long key = chunkKey(pcx + dx, pcz + dz);
                    if (!st.masked.contains(key) && st.pendingSet.add(key)) st.pending.add(key);
                }
            }
        }
    }

    /** Paper：区块数据刚发送给玩家。 */
    void onChunkSent(Player p, Chunk chunk) {
        if (!enabled || !enabledFor(p)) return;
        PlayerState st = state(p);
        long key = chunkKey(chunk.getX(), chunk.getZ());
        st.masked.remove(key);
        if (st.pendingSet.add(key)) st.pending.addFirst(key);
    }

    /** Paper：区块从客户端卸载。 */
    void onChunkUnsent(Player p, Chunk chunk) {
        PlayerState st = players.get(p.getUniqueId());
        if (st == null) return;
        long key = chunkKey(chunk.getX(), chunk.getZ());
        st.masked.remove(key);
        if (st.pendingSet.remove(key)) st.pending.remove(key);
    }

    private int processPending(Player p, PlayerState st, int budget) {
        World world = p.getWorld();
        Map<Long, Map<Long, BlockData>> wc = cache.computeIfAbsent(world.getName(), k -> new ConcurrentHashMap<>());
        int n = st.pending.size();
        for (int i = 0; i < n && !st.pending.isEmpty(); i++) {
            long key = st.pending.poll();
            Map<Long, BlockData> hidden = wc.get(key);
            if (hidden != null) {
                st.pendingSet.remove(key);
                st.masked.add(key);
                for (Map.Entry<Long, BlockData> e : hidden.entrySet()) {
                    st.sends.add(new long[]{e.getKey()});
                    st.sendData.add(e.getValue());
                }
                continue;
            }
            int cx = keyX(key), cz = keyZ(key);
            if (!world.isChunkLoaded(cx, cz)) { st.pendingSet.remove(key); continue; }
            String ck = world.getName() + ":" + key;
            if (!computing.contains(ck) && budget > 0) {
                budget--;
                startCompute(world, cx, cz, ck);
            }
            st.pending.add(key); // 等待计算完成
        }
        return budget;
    }

    private void flushSends(Player p, PlayerState st) {
        World world = p.getWorld();
        Map<Long, Map<Long, BlockData>> wc = cache.get(world.getName());
        int sent = 0;
        while (sent < packetsPerTick && !st.sends.isEmpty()) {
            long pos = st.sends.poll()[0];
            BlockData data = st.sendData.poll();
            int x = posX(pos), y = posY(pos), z = posZ(pos);
            Map<Long, BlockData> hidden = wc == null ? null : wc.get(chunkKey(x >> 4, z >> 4));
            if (hidden == null || !hidden.containsKey(pos)) continue; // 已暴露
            p.sendBlockChange(new Location(world, x, y, z), data);
            sent++;
        }
    }

    // ======================================================================
    // 计算（主线程取快照，异步计算）
    // ======================================================================

    private void startCompute(World world, int cx, int cz, String computeKey) {
        computing.add(computeKey);
        ChunkSnapshot center = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
        ChunkSnapshot[] nb = new ChunkSnapshot[4]; // -x, +x, -z, +z
        int[][] off = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            int nx = cx + off[i][0], nz = cz + off[i][1];
            if (world.isChunkLoaded(nx, nz)) nb[i] = world.getChunkAt(nx, nz).getChunkSnapshot(false, false, false);
        }
        int minY = world.getMinHeight();
        int worldMax = world.getMaxHeight();
        int top = Math.min(maxY, worldMax - 1);
        Set<Material> oreSet = ores;
        String worldName = world.getName();
        long key = chunkKey(cx, cz);
        int version = versions.getOrDefault(computeKey, 0);
        Bukkit.getScheduler().runTaskAsynchronously(m.plugin(), () -> {
            Map<Long, BlockData> hidden = null;
            try {
                hidden = new ConcurrentHashMap<>();
                int baseX = cx << 4, baseZ = cz << 4;
                for (int y = minY; y <= top; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            Material mat = center.getBlockType(x, y, z);
                            if (!oreSet.contains(mat)) continue;
                            if (!enclosed(center, nb, x, y, z, minY, worldMax)) continue;
                            hidden.put(posKey(baseX + x, y, baseZ + z), fakeFor(mat, y));
                        }
                    }
                }
            } catch (Throwable t) {
                m.logger().warning("假矿计算失败 " + worldName + " " + cx + "," + cz + ": " + t);
                hidden = null;
            }
            final Map<Long, BlockData> result = hidden;
            // 回主线程比对版本：计算期间区块内有方块暴露 / 区块卸载则丢弃结果（之后会按需重算）
            Bukkit.getScheduler().runTask(m.plugin(), () -> {
                computing.remove(computeKey);
                Integer now = versions.remove(computeKey);
                if (result == null || !enabled || task == null) return;
                if (now != null && now != version) return;
                World w = Bukkit.getWorld(worldName);
                if (w == null || !w.isChunkLoaded(cx, cz)) return;
                Map<Long, Map<Long, BlockData>> wc = cache.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
                Map<Long, BlockData> prev = wc.put(key, result);
                hiddenTotal.addAndGet(result.size() - (prev == null ? 0 : prev.size()));
            });
        });
    }

    /** 区块内容在计算期间发生变化：使在途的计算结果失效（主线程调用）。 */
    private void bump(String worldName, long chunkKey) {
        if (computing.isEmpty()) return;
        String ck = worldName + ":" + chunkKey;
        if (computing.contains(ck)) versions.merge(ck, 1, Integer::sum);
    }

    private static boolean enclosed(ChunkSnapshot c, ChunkSnapshot[] nb, int x, int y, int z, int minY, int maxH) {
        return occluding(c, nb, x, y + 1, z, minY, maxH) && occluding(c, nb, x, y - 1, z, minY, maxH)
                && occluding(c, nb, x + 1, y, z, minY, maxH) && occluding(c, nb, x - 1, y, z, minY, maxH)
                && occluding(c, nb, x, y, z + 1, minY, maxH) && occluding(c, nb, x, y, z - 1, minY, maxH);
    }

    private static boolean occluding(ChunkSnapshot c, ChunkSnapshot[] nb, int x, int y, int z, int minY, int maxH) {
        if (y < minY) return true;
        if (y >= maxH) return false;
        ChunkSnapshot s = c;
        if (x < 0) { s = nb[0]; x += 16; }
        else if (x > 15) { s = nb[1]; x -= 16; }
        else if (z < 0) { s = nb[2]; z += 16; }
        else if (z > 15) { s = nb[3]; z -= 16; }
        if (s == null) return false; // 邻区块未加载：保守视为暴露
        return s.getBlockType(x, y, z).isOccluding();
    }

    private BlockData fakeFor(Material mat, int y) {
        String n = mat.name();
        if (n.startsWith("NETHER_") || mat == Material.ANCIENT_DEBRIS) return netherrack;
        if (n.startsWith("DEEPSLATE_") || y < 0) return deepslate;
        return stone;
    }

    // ======================================================================
    // 暴露 → 还原真实方块
    // ======================================================================

    private void revealAround(Collection<Block> blocks) {
        if (blocks.isEmpty() || (cache.isEmpty() && computing.isEmpty())) return;
        List<Block> toSend = new ArrayList<>();
        for (Block b : blocks) {
            checkReveal(b, toSend);
            for (BlockFace f : FACES) checkReveal(b.getRelative(f), toSend);
        }
        if (toSend.isEmpty()) return;
        // 下一 tick 发送（等待本次方块变更落地）
        m.runTask(() -> {
            for (Block b : toSend) {
                World w = b.getWorld();
                Location loc = b.getLocation();
                BlockData real = b.getBlockData();
                double max = (double) revealDistance * revealDistance;
                for (Player p : w.getPlayers()) {
                    if (p.getLocation().distanceSquared(loc) <= max) p.sendBlockChange(loc, real);
                }
            }
        });
    }

    private void checkReveal(Block b, List<Block> out) {
        String wn = b.getWorld().getName();
        long ck = chunkKey(b.getX() >> 4, b.getZ() >> 4);
        bump(wn, ck);
        Map<Long, Map<Long, BlockData>> wc = cache.get(wn);
        if (wc == null) return;
        Map<Long, BlockData> hidden = wc.get(ck);
        if (hidden == null) return;
        if (hidden.remove(posKey(b.getX(), b.getY(), b.getZ())) != null) {
            hiddenTotal.decrementAndGet();
            out.add(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { revealAround(List.of(e.getBlock())); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { revealAround(e.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { revealAround(e.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        List<Block> l = new ArrayList<>(e.getBlocks());
        l.add(e.getBlock().getRelative(e.getDirection()));
        revealAround(l);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        List<Block> l = new ArrayList<>(e.getBlocks());
        l.add(e.getBlock().getRelative(e.getDirection()));
        revealAround(l);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { revealAround(List.of(e.getBlock())); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (cache.isEmpty()) return;
        revealAround(List.of(e.getToBlock()));
    }

    /** 末影人搬方块、沙子/沙砾等下落方块开始下落或落地。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) { revealAround(List.of(e.getBlock())); }

    /** 冰融化、珊瑚死亡等方块褪变。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { revealAround(List.of(e.getBlock())); }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        String wn = e.getWorld().getName();
        long ck = chunkKey(e.getChunk().getX(), e.getChunk().getZ());
        bump(wn, ck);
        Map<Long, Map<Long, BlockData>> wc = cache.get(wn);
        if (wc == null) return;
        Map<Long, BlockData> prev = wc.remove(ck);
        if (prev != null) hiddenTotal.addAndGet(-prev.size());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { players.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) { players.remove(e.getPlayer().getUniqueId()); }

    // ======================================================================
    // 坐标打包
    // ======================================================================

    static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    static int keyX(long k) { return (int) (k >> 32); }

    static int keyZ(long k) { return (int) k; }

    static long posKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    static int posX(long k) { return (int) (k >> 38); }

    static int posZ(long k) { return (int) (k << 26 >> 38); }

    static int posY(long k) { return (int) (k << 52 >> 52); }
}
