/*
 * Derived from LagFixer by lychee/lajczik, GPL-3.0.
 * https://github.com/lajczik/lagfixer/tree/0dede033f7dc2950a08f8a461bfcff84f64c7d3c
 * Sources: RedstoneLimiterModule, ExplosionOptimizerModule, InstantLeafDecayModule,
 * HopperOptimizerModule. Modified 2026-10-19 for LC/Paper: synchronous bounded
 * event processing; bounded natural leaf drops; no redstone block destruction,
 * simulated explosion damage, or NMS.
 * Distributed under GNU GPL version 3; see project LICENSE and upstream notices.
 */
package com.lunarboreal.performance.lagfixer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Tag;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** Ports the upstream event optimizations, with explicit bounded safety adaptations. */
public final class WorldOptimizations implements AutoCloseable {
    static final String REDSTONE = "redstone", EXPLOSION = "explosion",
            LEAVES = "leaf-decay", HOPPER = "hopper";
    private final PortContext c;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean active;
    private long tick;
    private Redstone redstone;
    private Explosions explosions;
    private Leaves leaves;
    private Hoppers hoppers;

    public WorldOptimizations(PortContext context) { c = Objects.requireNonNull(context); }
    public void start() {
        if (active) return;
        active = true;
        redstone = new Redstone(); explosions = new Explosions(); leaves = new Leaves(); hoppers = new Hoppers();
        listen(REDSTONE, redstone); listen(EXPLOSION, explosions); listen(LEAVES, leaves); listen(HOPPER, hoppers);
        tick = 0;
        tasks.add(c.timer(() -> {
            if (!active) return;
            tick++;
            leaves.process();
            if (tick % 40 == 0) redstone.windows.clear();
            if (tick % 20 == 0) redstone.clicks.expire(tick);
            if (tick % 20 == 0) { hoppers.chunkWindows.clear(); hoppers.transfers.expire(tick); explosions.expire(); }
        }, 1, 1));
    }
    private void listen(String id, Listener listener) {
        if (c.enabled(id)) { c.listen(listener); listeners.add(listener); }
    }
    private boolean allows(String id, World world) { return active && c.enabled(id) && c.allows(id, world); }
    private ConfigurationSection section(String id) { return c.section(id); }
    static int bounded(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
    private int integer(String id, String key, int fallback, int low, int high) {
        return bounded(section(id).getInt(key, fallback), low, high);
    }
    private double number(String id, String key, double fallback, double low, double high) {
        double n = section(id).getDouble(key, fallback);
        return Double.isFinite(n) ? Math.max(low, Math.min(high, n)) : fallback;
    }
    private boolean flag(String id, String key, boolean fallback) { return section(id).getBoolean(key, fallback); }
    @Override public void close() {
        active = false;
        tasks.forEach(BukkitTask::cancel); tasks.clear();
        listeners.forEach(HandlerList::unregisterAll); listeners.clear();
        if (redstone != null) { redstone.windows.clear(); redstone.clicks.clear(); }
        if (leaves != null) { leaves.pending.clear(); leaves.queued.clear(); leaves.origins.clear(); }
        if (hoppers != null) { hoppers.transfers.clear(); hoppers.chunkWindows.clear(); }
        if (explosions != null) explosions.regions.clear();
    }

    private record Position(UUID world, int x, int y, int z) {
        static Position block(Location l) { return new Position(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ()); }
        static Position chunk(Block b) { return new Position(b.getWorld().getUID(), b.getX() >> 4, 0, b.getZ() >> 4); }
    }
    /** Capacity exhaustion fails open: it never creates an unbounded cache or locks new locations. */
    static final class WindowBudget<K> {
        private record Window(long generation, int count) {}
        private final Map<K, Window> entries = new HashMap<>();
        boolean allow(K key, long generation, int cost, int limit, int capacity) {
            Window old = entries.get(key);
            if (old == null && entries.size() >= capacity) return true;
            int previous = old == null || old.generation != generation ? 0 : old.count;
            int next = (int) Math.min(Integer.MAX_VALUE, (long) previous + Math.max(1, cost));
            entries.put(key, new Window(generation, next));
            return next <= limit;
        }
        int size() { return entries.size(); }
        void clear() { entries.clear(); }
    }
    static final class Cooldowns<K> {
        private final Map<K, Long> deadlines = new HashMap<>();
        boolean allow(K key, long now, int delay, int capacity) {
            Long until = deadlines.get(key);
            if (until != null && until > now) return false;
            if (until == null && deadlines.size() >= capacity) return true;
            deadlines.put(key, now + delay);
            return true;
        }
        void expire(long now) { deadlines.values().removeIf(until -> until <= now); }
        int size() { return deadlines.size(); }
        void clear() { deadlines.clear(); }
    }

    private final class Redstone implements Listener {
        private final WindowBudget<String> windows = new WindowBudget<>();
        private final Cooldowns<String> clicks = new Cooldowns<>();
        private boolean cooldown(UUID player, Material material) {
            int ticks = integer(REDSTONE, "click-cooldown-ticks", 0, 0, 1200);
            return ticks > 0 && !clicks.allow(player + ":" + material, tick, ticks, 4096);
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void place(BlockPlaceEvent e) {
            Material type = e.getBlockPlaced().getType();
            if (allows(REDSTONE, e.getBlock().getWorld()) && (type == Material.REDSTONE_TORCH || type == Material.REDSTONE_WALL_TORCH)
                    && cooldown(e.getPlayer().getUniqueId(), type)) { e.setCancelled(true); c.count(REDSTONE); }
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void interact(PlayerInteractEvent e) {
            Block b = e.getClickedBlock();
            if (b != null && e.getAction() == Action.RIGHT_CLICK_BLOCK && allows(REDSTONE, b.getWorld())
                    && (b.getType() == Material.LEVER || Tag.BUTTONS.isTagged(b.getType()))
                    && cooldown(e.getPlayer().getUniqueId(), b.getType())) { e.setCancelled(true); c.count(REDSTONE); }
        }
        private String key(Block b, String type) { return Position.chunk(b) + type; }
        @EventHandler(priority = EventPriority.HIGH)
        public void redstone(BlockRedstoneEvent e) {
            if (!allows(REDSTONE, e.getBlock().getWorld()) || e.getOldCurrent() != 0 || e.getNewCurrent() == 0) return;
            if (!windows.allow(key(e.getBlock(), "redstone"), tick / 40, 1,
                    integer(REDSTONE, "ticks_limit.redstone", 500, 1, 100000), 4096)) {
                e.setNewCurrent(e.getOldCurrent()); c.count(REDSTONE);
            }
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void piston(BlockPistonExtendEvent e) {
            if (!allows(REDSTONE, e.getBlock().getWorld())) return;
            Set<String> blacklist = new HashSet<>(section(REDSTONE).getStringList("piston.push_blacklist"));
            boolean blocked = e.getBlocks().stream().anyMatch(b -> blacklist.contains(b.getType().name()));
            if (blocked || !windows.allow(key(e.getBlock(), "piston"), tick / 40, Math.max(1, e.getBlocks().size()),
                    integer(REDSTONE, "ticks_limit.piston", 200, 1, 100000), 4096)) {
                e.setCancelled(true); c.count(REDSTONE);
            }
        }
    }
    private final class Explosions implements Listener {
        private record Region(UUID world, double x, double y, double z, long until) {}
        private final ArrayDeque<Region> regions = new ArrayDeque<>();
        void expire() { regions.removeIf(r -> r.until <= tick); }
        private boolean chain(Location loc) {
            if (!flag(EXPLOSION, "anti_chain.enabled", false)) return false;
            expire();
            double radius = number(EXPLOSION, "anti_chain.radius", 5, 0.5, 32);
            int nearby = 0;
            for (Region r : regions) {
                double dx = r.x - loc.getX(), dy = r.y - loc.getY(), dz = r.z - loc.getZ();
                if (r.world.equals(loc.getWorld().getUID()) && dx * dx + dy * dy + dz * dz <= radius * radius) nearby++;
            }
            if (nearby >= integer(EXPLOSION, "anti_chain.max_chain_size", 8, 1, 256)) return true;
            if (regions.size() < 1024) regions.addLast(new Region(loc.getWorld().getUID(), loc.getX(), loc.getY(), loc.getZ(),
                    tick + integer(EXPLOSION, "anti_chain.cooldown-ticks", 20, 1, 1200)));
            return false;
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void prime(ExplosionPrimeEvent e) {
            if (!allows(EXPLOSION, e.getEntity().getWorld())) return;
            if (flag(EXPLOSION, "yield_limit.enabled", false)) {
                float max = (float) number(EXPLOSION, "maximum-radius", 4, 0, 32);
                if (e.getRadius() > max) { e.setRadius(max); c.count(EXPLOSION); }
            }
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void entity(EntityExplodeEvent e) {
            if (!allows(EXPLOSION, e.getLocation().getWorld())) return;
            if (chain(e.getLocation())) { e.setCancelled(true); c.count(EXPLOSION); return; }
            if (flag(EXPLOSION, "yield_limit.enabled", false)) e.setYield(Math.min(e.getYield(), (float) number(EXPLOSION, "maximum-drop-yield", 1, 0, 1)));
            if (flag(EXPLOSION, "anti_chain.prevent_block_ignition", false)) e.blockList().removeIf(b -> b.getType() == Material.TNT);
        }
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void block(BlockExplodeEvent e) {
            if (!allows(EXPLOSION, e.getBlock().getWorld())) return;
            if (chain(e.getBlock().getLocation())) { e.setCancelled(true); c.count(EXPLOSION); return; }
            if (flag(EXPLOSION, "yield_limit.enabled", false)) e.setYield(Math.min(e.getYield(), (float) number(EXPLOSION, "maximum-drop-yield", 1, 0, 1)));
            if (flag(EXPLOSION, "anti_chain.prevent_block_ignition", false)) e.blockList().removeIf(b -> b.getType() == Material.TNT);
        }
    }
    private final class Leaves implements Listener {
        private final ArrayDeque<Position> pending = new ArrayDeque<>();
        private final Set<Position> queued = new HashSet<>();
        private final Map<Position, Long> origins = new LinkedHashMap<>();
        private boolean firing;
        private final BlockFace[] faces = {BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.UP, BlockFace.DOWN};
        private boolean valid(Block block) {
            return block.getBlockData() instanceof org.bukkit.block.data.type.Leaves data
                    && !data.isPersistent() && data.getDistance() == 7;
        }
        private void queue(Block b) {
            if (!b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) || !valid(b)) return;
            Position p = Position.block(b.getLocation());
            if (pending.size() < integer(LEAVES, "queue-capacity", 4096, 64, 65536) && queued.add(p)) pending.addLast(p);
        }
        private void neighbors(Block b) {
            for (BlockFace face : faces) {
                int x = b.getX() + face.getModX(), y = b.getY() + face.getModY(), z = b.getZ() + face.getModZ();
                World w = b.getWorld();
                if (y >= w.getMinHeight() && y < w.getMaxHeight() && w.isChunkLoaded(x >> 4, z >> 4)) queue(w.getBlockAt(x, y, z));
            }
        }
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void broken(BlockBreakEvent e) {
            if (!allows(LEAVES, e.getBlock().getWorld())) return;
            // Wait for vanilla distance propagation after removal of a log or leaf.
            Position p = Position.block(e.getBlock().getLocation());
            if (origins.size() < 4096) origins.put(p, tick + 2);
        }
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void decay(LeavesDecayEvent e) {
            if (!firing && allows(LEAVES, e.getBlock().getWorld())) neighbors(e.getBlock());
        }
        void process() {
            if (!active || !c.enabled(LEAVES)) return;
            int budget = integer(LEAVES, "max-decays-per-tick", 32, 1, 256);
            var originsIterator = origins.entrySet().iterator();
            int originBudget = budget;
            while (originsIterator.hasNext() && originBudget-- > 0) {
                var entry = originsIterator.next();
                if (entry.getValue() > tick) continue;
                Position p = entry.getKey(); originsIterator.remove();
                World w = Bukkit.getWorld(p.world);
                if (w != null && allows(LEAVES, w) && w.isChunkLoaded(p.x >> 4, p.z >> 4)) neighbors(w.getBlockAt(p.x, p.y, p.z));
            }
            while (active && budget-- > 0 && !pending.isEmpty()) {
                Position p = pending.removeFirst(); queued.remove(p);
                World w = Bukkit.getWorld(p.world);
                if (w == null || !allows(LEAVES, w) || !w.isChunkLoaded(p.x >> 4, p.z >> 4)) continue;
                Block b = w.getBlockAt(p.x, p.y, p.z);
                if (!valid(b)) continue;
                LeavesDecayEvent decay = new LeavesDecayEvent(b);
                firing = true;
                try { Bukkit.getPluginManager().callEvent(decay); } finally { firing = false; }
                if (decay.isCancelled() || !active || !allows(LEAVES, w) || !w.isChunkLoaded(p.x >> 4, p.z >> 4) || !valid(b)) continue;
                if (b.breakNaturally()) { c.count(LEAVES); neighbors(b); }
            }
        }
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void chunkUnload(ChunkUnloadEvent e) {
            UUID world = e.getWorld().getUID(); int x = e.getChunk().getX(), z = e.getChunk().getZ();
            pending.removeIf(p -> p.world.equals(world) && p.x >> 4 == x && p.z >> 4 == z);
            queued.removeIf(p -> p.world.equals(world) && p.x >> 4 == x && p.z >> 4 == z);
            origins.keySet().removeIf(p -> p.world.equals(world) && p.x >> 4 == x && p.z >> 4 == z);
        }
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void worldUnload(WorldUnloadEvent e) {
            pending.removeIf(p -> p.world.equals(e.getWorld().getUID()));
            queued.removeIf(p -> p.world.equals(e.getWorld().getUID()));
            origins.keySet().removeIf(p -> p.world.equals(e.getWorld().getUID()));
        }
    }
    private final class Hoppers implements Listener {
        private final Cooldowns<Position> transfers = new Cooldowns<>();
        private final WindowBudget<Position> chunkWindows = new WindowBudget<>();
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void transfer(InventoryMoveItemEvent e) {
            if (e.getInitiator().getType() != InventoryType.HOPPER) return;
            Location loc = e.getInitiator().getLocation();
            if (loc == null || !allows(HOPPER, loc.getWorld())) return;
            // Transfer cooldown retains upstream throttling intent without its absent NMS backend.
            int delay = integer(HOPPER, "transfer-cooldown-ticks", 8, 1, 200);
            boolean throttle = flag(HOPPER, "smart_throttling.enabled", true)
                    && !transfers.allow(Position.block(loc), tick, delay, 8192);
            boolean chunkLimit = flag(HOPPER, "chunk_limiting.enabled", false)
                    && !chunkWindows.allow(Position.chunk(loc.getBlock()), tick / 20, 1,
                    integer(HOPPER, "chunk_limiting.max-transfers-per-second", 256, 1, 65536), 4096);
            if (throttle || chunkLimit) { e.setCancelled(true); c.count(HOPPER); }
        }
    }
}
