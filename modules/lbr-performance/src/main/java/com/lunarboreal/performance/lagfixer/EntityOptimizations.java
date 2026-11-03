/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Derived from LagFixer by Lychee (xyz.lychee.lagfixer.modules):
 * EntityLimiterModule, WorldCleanerModule, TrashDisposalModule,
 * AbilityLimiterModule and AFKOptimizerModule.
 * Upstream: https://github.com/lajczik/lagfixer
 * LunarCore adaptation: Bukkit-only main-thread execution, bounded state,
 * protected entities/items and reversible visibility/cooldown changes.
 * This file is distributed under the GNU General Public License version 3.
 */
package com.lunarboreal.performance.lagfixer;

import com.lunarboreal.api.LBR;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Safe ports of the five upstream modules; start/close must run on the Bukkit thread. */
public final class EntityOptimizations implements Listener, AutoCloseable {
    private static final String LIMITER = "entity-limiter", CLEANER = "world-cleaner", TRASH = "trash-disposal", ABILITY = "ability-limiter", AFK = "afk";
    private final PortContext c;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Map<UUID, AfkState> afk = new LinkedHashMap<>();
    private final Map<UUID, Inventory> trash = new LinkedHashMap<>();
    private final Map<UUID, Map<Material, Cooldown>> cooldowns = new HashMap<>();
    private final Map<ChunkKey, Integer> offsets = new LinkedHashMap<>();
    private Inventory globalTrash;
    private Inventory abyss;
    private UUID globalTrashOwner;
    private int pendingBoosts;
    private boolean running;
    private long ticks, cleanerDue;
    private int worldCursor, chunkCursor, afkCursor;
    private record ChunkKey(UUID world, int x, int z) {}
    private record Cooldown(long previousUntil, long appliedUntil) {}
    private static final class AfkState {
        Location last;
        long idleSince;
        Location back;
        final Set<UUID> hidden = new LinkedHashSet<>();
        AfkState(Location last, long now) { this.last = last; idleSince = now; }
    }
    public EntityOptimizations(PortContext c) { this.c = c; }
    private ConfigurationSection cfg(String id) { return c.section(id); }
    private boolean active(String id, World world) { return running && c.enabled(id) && c.allows(id, world); }
    private int number(String id, String key, int fallback, int min, int max) { return bounded(cfg(id).getInt(key, fallback), min, max); }
    static int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    static boolean afkByMode(boolean position, boolean rotation, int mode) {
        return switch (mode) { case 0 -> position || rotation; case 1 -> position && rotation; case 2 -> position; case 3 -> rotation; default -> false; };
    }
    @Override public void close() {
        running = false;
        tasks.forEach(BukkitTask::cancel); tasks.clear(); pendingBoosts = 0;
        HandlerList.unregisterAll(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            restoreAfk(player, afk.get(player.getUniqueId()), true);
            restoreCooldown(player);
        }
        // Closing suppresses the normal disposal-on-close handler; return all unpurged contents.
        for (var entry : new ArrayList<>(trash.entrySet())) {
            closeViewers(entry.getValue()); returnContents(Bukkit.getPlayer(entry.getKey()), entry.getValue());
        }
        if (globalTrash != null) { closeViewers(globalTrash); returnContents(globalTrashOwner == null ? null : Bukkit.getPlayer(globalTrashOwner), globalTrash); }
        if (abyss != null) {
            closeViewers(abyss);
            Player recipient = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            returnContents(recipient, abyss);
        }
        trash.clear(); globalTrash = null; abyss = null; globalTrashOwner = null; afk.clear(); cooldowns.clear(); offsets.clear();
    }
    public void start() {
        if (running) return;
        running = true;
        c.listen(this);
        cleanerDue = number(CLEANER, "interval-seconds", 300, 10, 86400) * 20L;
        tasks.add(c.timer(this::tick, 20L, 20L));
    }
    private void tick() {
        if (!running) return;
        ticks += 20;
        if ((c.enabled(CLEANER) || c.enabled(LIMITER) && cfg(LIMITER).getBoolean("overflow-purge.enabled", false)) && ticks >= cleanerDue) scanBatch();
        if (c.enabled(AFK)) updateAfk();
        else for (Player p : Bukkit.getOnlinePlayers()) restoreAfk(p, afk.remove(p.getUniqueId()), true);
        if (c.enabled(TRASH) && cfg(TRASH).getBoolean("cleanup.enabled", false)
                && ticks % (number(TRASH, "cleanup.interval-seconds", 300, 10, 86400) * 20L) == 0) {
            trash.values().forEach(inv -> { if (inv.getViewers().isEmpty()) inv.clear(); });
            if (globalTrash != null && globalTrash.getViewers().isEmpty()) globalTrash.clear();
        }
        // Keep only online owners; visibility pairs and inventories have hard caps as well.
        Set<UUID> online = new HashSet<>(); Bukkit.getOnlinePlayers().forEach(p -> online.add(p.getUniqueId()));
        afk.keySet().retainAll(online); cooldowns.keySet().retainAll(online);
    }
    /** Player creations/possessions, metadata and special plugin entities never become purge targets. */
    static boolean protectedEntity(Entity e) {
        if (e instanceof HumanEntity || e.customName() != null || !e.getPersistentDataContainer().isEmpty()
                || e.isInvulnerable() || !e.getPassengers().isEmpty() || e.isInsideVehicle()) return true;
        if (e instanceof Tameable tame && tame.isTamed()) return true;
        if (e instanceof Animals animal && animal.getBreedCause() != null) return true;
        if (e instanceof LivingEntity living && living.isLeashed()) return true;
        if (e instanceof Item item) return item.getThrower() != null || item.getOwner() != null || item.getPickupDelay() >= 200 || protectedStack(item.getItemStack());
        if (e instanceof Projectile projectile && projectile.getShooter() instanceof Player) return true;
        return false;
    }
    static boolean protectedStack(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var meta = item.getItemMeta();
        return meta.hasDisplayName() || meta.hasLore() || !meta.getPersistentDataContainer().isEmpty() || meta.hasEnchants();
    }
    private boolean whitelisted(String id, String key, Entity entity) { return cfg(id).getStringList(key).stream().anyMatch(name -> name.equalsIgnoreCase(entity.getType().name())); }
    private int category(Entity e) {
        if (e instanceof Mob) return 0; if (e instanceof Item) return 1; if (e instanceof Vehicle) return 2; if (e instanceof Projectile) return 3; return -1;
    }
    private boolean overLimit(Entity incoming) {
        if (!active(LIMITER, incoming.getWorld()) || protectedEntity(incoming) || whitelisted(LIMITER, "whitelist", incoming)) return false;
        int category = category(incoming);
        if (category < 0) return false;
        String key = List.of("creatures", "items", "vehicles", "projectiles").get(category);
        int limit = number(LIMITER, key, 0, 0, 10000);
        if (limit == 0) return false;
        int count = 0;
        // Event limits stop at the threshold; no loaded-world entity cache is retained.
        for (Entity entity : incoming.getChunk().getEntities()) {
            if (entity.equals(incoming) || category(entity) != category || whitelisted(LIMITER, "whitelist", entity)) continue;
            if (++count >= limit) { c.count(LIMITER); return true; }
        }
        return false;
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void creature(CreatureSpawnEvent event) {
        List<String> reasons = cfg(LIMITER).getStringList("reasons");
        if (reasons.isEmpty() || reasons.stream().noneMatch(reason -> reason.equalsIgnoreCase(event.getSpawnReason().name()))) return;
        if (overLimit(event.getEntity())) event.setCancelled(true);
    }
    // ItemSpawnEvent is intentionally not cancelled: Bukkit may not yet have attached
    // a player drop's thrower. Item overflow is handled only after ownership is known.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void vehicle(VehicleCreateEvent event) { if (overLimit(event.getVehicle())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void projectile(ProjectileLaunchEvent event) { if (overLimit(event.getEntity())) event.setCancelled(true); }

    /* WorldCleaner ports upstream type/list-mode/item-age filters, replacing the
       single world.getEntities() purge with bounded, rotating loaded-chunk batches. */
    private void scanBatch() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return;
        int chunkBudget = number(CLEANER, "chunks-per-batch", 2, 1, 16);
        int entityBudget = number(CLEANER, "entities-per-batch", 128, 1, 2048);
        int visitedWorlds = 0;
        while (chunkBudget > 0 && entityBudget > 0 && visitedWorlds <= worlds.size()) {
            World world = worlds.get(Math.floorMod(worldCursor, worlds.size()));
            boolean clean = active(CLEANER, world);
            boolean overflow = active(LIMITER, world) && cfg(LIMITER).getBoolean("overflow-purge.enabled", false);
            if (!clean && !overflow) { worldCursor++; chunkCursor = 0; visitedWorlds++; continue; }
            Chunk[] chunks = world.getLoadedChunks();
            if (chunkCursor >= chunks.length) { worldCursor++; chunkCursor = 0; visitedWorlds++; continue; }
            Chunk chunk = chunks[chunkCursor++]; chunkBudget--;
            if (!chunk.isLoaded()) continue;
            Entity[] entities = chunk.getEntities();
            ChunkKey key = new ChunkKey(world.getUID(), chunk.getX(), chunk.getZ());
            int start = offsets.getOrDefault(key, 0);
            if (start >= entities.length) start = 0;
            int amount = Math.min(entityBudget, entities.length);
            // Guarantee forward progress through unusually dense chunks over successive sweeps.
            if (start > 0 && amount == entities.length) start = 0;
            int[] counts = new int[4];
            for (int i = 0; i < amount; i++) {
                Entity entity = entities[(start + i) % entities.length];
                int kind = category(entity);
                boolean excess = false;
                // Only purge fully inspected chunks; oversized chunks use spawn prevention,
                // never partial counts that could remove arbitrary farm entities.
                if (overflow && amount == entities.length && kind >= 0 && !whitelisted(LIMITER, "whitelist", entity)) {
                    String type = List.of("creatures", "items", "vehicles", "projectiles").get(kind);
                    int limit = number(LIMITER, type, 0, 0, 10000);
                    double multiplier = cfg(LIMITER).getDouble("overflow-purge.limit-multiplier", 2);
                    if (!Double.isFinite(multiplier)) multiplier = 2;
                    excess = ++counts[kind] > limit * Math.max(1, Math.min(10, multiplier)) && limit > 0
                            && cfg(LIMITER).getBoolean("overflow-purge.types." + type, false);
                }
                if (entity.isValid() && !protectedEntity(entity) && (excess || clean && shouldClean(entity))) {
                    if (entity instanceof Item item && cfg(CLEANER).getBoolean("items.abyss.enabled", false) && !collectAbyss(item)) continue;
                    entity.remove(); c.count(excess ? LIMITER : CLEANER);
                }
            }
            entityBudget -= amount;
            if (entities.length > amount) {
                if (offsets.size() >= 2048 && !offsets.containsKey(key)) offsets.remove(offsets.keySet().iterator().next());
                offsets.put(key, (start + amount) % entities.length);
            } else offsets.remove(key);
        }
        // A sweep finishes at the end of the world list, then waits the configured interval.
        if (worldCursor >= worlds.size()) {
            worldCursor = 0; chunkCursor = 0;
            cleanerDue = ticks + number(CLEANER, "interval-seconds", 300, 10, 86400) * 20L;
        }
    }
    private boolean shouldClean(Entity entity) {
        if (entity instanceof Item item) {
            if (!cfg(CLEANER).getBoolean("items.enabled", false) || item.getTicksLived() < number(CLEANER, "items.min-age-seconds", 300, 10, 86400) * 20L) return false;
            return cfg(CLEANER).getStringList("items.blacklist").stream().noneMatch(name -> name.equalsIgnoreCase(item.getItemStack().getType().name()));
        }
        if (entity instanceof Mob) return cfg(CLEANER).getBoolean("creatures.enabled", false) && listMatch(entity, "creatures");
        if (entity instanceof Projectile projectile) return cfg(CLEANER).getBoolean("projectiles.enabled", false)
                && projectile.getTicksLived() >= number(CLEANER, "projectiles.min-age-seconds", 60, 10, 86400) * 20L && listMatch(entity, "projectiles");
        return false;
    }
    private boolean listMatch(Entity entity, String kind) {
        return whitelisted(CLEANER, kind + ".list", entity) == cfg(CLEANER).getBoolean(kind + ".list-mode", true);
    }
    private boolean collectAbyss(Item entity) {
        if (abyss == null) abyss = Bukkit.createInventory(null, 54, Component.text("Abyss 回收箱"));
        // A full bounded recovery box leaves the original entity intact.
        if (abyss.firstEmpty() < 0) return false;
        abyss.setItem(abyss.firstEmpty(), entity.getItemStack().clone());
        return true;
    }
    public boolean openAbyss(Player player) {
        if (!active(CLEANER, player.getWorld()) || !cfg(CLEANER).getBoolean("items.abyss.enabled", false)
                || !player.hasPermission("lbr.performance.abyss") || abyss == null) return false;
        player.openInventory(abyss); return true;
    }
    private void returnContents(Player player, Inventory inv) {
        World fallback = Bukkit.getWorlds().stream().findFirst().orElse(null);
        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType().isAir()) continue;
            if (player != null && player.isOnline()) {
                player.getInventory().addItem(item.clone()).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left).setThrower(player.getUniqueId()));
            } else if (fallback != null) {
                Item dropped = fallback.dropItemNaturally(fallback.getSpawnLocation(), item.clone());
                // Mark the returned item as player-owned so subsequent cleaners preserve it.
                dropped.setThrower(new UUID(0, 1));
            } else c.logger().warning("No loaded world available to return disposal inventory contents");
        }
        inv.clear();
    }
    /** Called by the framework's command, avoiding global command interception. */
    public boolean openTrash(Player player) {
        if (!active(TRASH, player.getWorld()) || !player.hasPermission(cfg(TRASH).getString("permission", "lbr.performance.trash"))) return false;
        Inventory inv;
        if (cfg(TRASH).getBoolean("global", false)) {
            if (globalTrash == null) globalTrash = Bukkit.createInventory(null, 54, Component.text("共享垃圾箱（物品会被删除）"));
            if (globalTrashOwner == null) globalTrashOwner = player.getUniqueId();
            inv = globalTrash;
        } else {
            inv = trash.get(player.getUniqueId());
            if (inv == null) {
                if (trash.size() >= number(TRASH, "max-inventories", 128, 1, 1024)) return false;
                inv = Bukkit.createInventory(null, 54, Component.text("垃圾箱（物品会被删除）")); trash.put(player.getUniqueId(), inv);
            }
        }
        player.openInventory(inv); return true;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (!active(TRASH, event.getPlayer().getWorld()) || !cfg(TRASH).getBoolean("forced-trash", false)
                || event.getPlayer().hasPermission("lbr.performance.trash.bypass")) return;
        event.setCancelled(true); c.count(TRASH);
        event.getPlayer().sendMessage(Component.text("此世界请使用垃圾箱处理物品。"));
    }
    @EventHandler public void inventoryClosed(InventoryCloseEvent event) {
        if (!running) return;
        UUID id = event.getPlayer().getUniqueId();
        if (event.getInventory() == trash.get(id) && !cfg(TRASH).getBoolean("store-items", false)) {
            trash.remove(id).clear(); c.count(TRASH);
        }
    }
    private void closeViewers(Inventory inv) { for (HumanEntity viewer : new ArrayList<>(inv.getViewers())) viewer.closeInventory(); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void riptide(PlayerRiptideEvent event) {
        Player player = event.getPlayer();
        if (!active(ABILITY, player.getWorld()) || player.hasPermission("lbr.performance.ability.bypass")) return;
        setCooldown(player, Material.TRIDENT, number(ABILITY, "trident-riptide.cooldown-ticks", 0, 0, 12000));
        damage(player, event.getItem(), number(ABILITY, "trident-riptide.additional-durability-loss", 0, 0, 100));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        Player player = event.getPlayer(); ItemStack item = event.getItem();
        if (!active(ABILITY, player.getWorld()) || player.hasPermission("lbr.performance.ability.bypass") || !player.isGliding()
                || item == null || item.getType() != Material.FIREWORK_ROCKET || player.hasCooldown(Material.FIREWORK_ROCKET)
                || event.useItemInHand() == Event.Result.DENY
                || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Delay one tick as upstream does, so the triggering rocket remains usable.
        int delay = number(ABILITY, "elytra-boost.cooldown-ticks", 0, 0, 12000);
        if (pendingBoosts >= 128) return;
        pendingBoosts++;
        BukkitTask[] delayed = new BukkitTask[1];
        delayed[0] = c.timer(() -> {
            delayed[0].cancel(); tasks.remove(delayed[0]);
            pendingBoosts = Math.max(0, pendingBoosts - 1);
            if (!running || !player.isOnline() || !active(ABILITY, player.getWorld())) return;
            setCooldown(player, Material.FIREWORK_ROCKET, delay);
            ItemStack chest = player.getInventory().getChestplate();
            if (chest != null && chest.getType() == Material.ELYTRA) damage(player, chest, number(ABILITY, "elytra-boost.additional-durability-loss", 0, 0, 100));
        }, 1L, 1L);
        tasks.add(delayed[0]);
    }
    private void setCooldown(Player player, Material material, int duration) {
        if (duration < 1 || player.getCooldown(material) >= duration) return;
        if (!cooldowns.containsKey(player.getUniqueId()) && cooldowns.size() >= 1024) return;
        Map<Material, Cooldown> old = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(Material.class));
        long now = Bukkit.getCurrentTick();
        Cooldown previous = old.get(material);
        long original = previous != null && previous.appliedUntil() > now ? previous.previousUntil() : now + player.getCooldown(material);
        player.setCooldown(material, duration); old.put(material, new Cooldown(original, now + duration)); c.count(ABILITY);
    }
    private void restoreCooldown(Player player) {
        Map<Material, Cooldown> entries = cooldowns.remove(player.getUniqueId());
        if (entries == null) return;
        long now = Bukkit.getCurrentTick();
        entries.forEach((material, old) -> {
            // Do not replace a different cooldown subsequently applied by another plugin.
            if (Math.abs(player.getCooldown(material) - Math.max(0, old.appliedUntil() - now)) <= 1)
                player.setCooldown(material, (int) Math.max(0, old.previousUntil() - now));
        });
    }
    private void damage(Player player, ItemStack item, int loss) {
        if (loss == 0 || item == null || player.getGameMode() == GameMode.CREATIVE || protectedStack(item)) return;
        if (!(item.getItemMeta() instanceof Damageable meta) || meta.isUnbreakable()) return;
        int actual = 0, unbreaking = item.getEnchantmentLevel(Enchantment.UNBREAKING);
        for (int i = 0; i < loss; i++) if (ThreadLocalRandom.current().nextInt(unbreaking + 1) == 0) actual++;
        // Keep the item rather than bypassing break events or deleting the player's item.
        meta.setDamage(Math.min(Math.max(0, item.getType().getMaxDurability() - 1), meta.getDamage() + actual)); item.setItemMeta(meta);
    }
    private void updateAfk() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;
        int maxPlayers = number(AFK, "players-per-check", 16, 1, 128), maxTracked = number(AFK, "max-tracked-players", 512, 1, 4096);
        for (int index = 0; index < Math.min(players.size(), maxPlayers); index++) {
            Player player = players.get(Math.floorMod(afkCursor++, players.size())); UUID id = player.getUniqueId();
            if (!active(AFK, player.getWorld()) || player.hasPermission("lbr.performance.afk.bypass")) { restoreAfk(player, afk.remove(id), true); continue; }
            if (!afk.containsKey(id) && afk.size() >= maxTracked) continue;
            long now = System.nanoTime(); Location current = player.getLocation();
            AfkState state = afk.get(id);
            if (state == null) {
                state = new AfkState(current, now);
                afk.put(id, state);
            }
            boolean sameWorld = Objects.equals(current.getWorld(), state.last.getWorld());
            boolean position = sameWorld && current.getBlockX() == state.last.getBlockX() && current.getBlockY() == state.last.getBlockY() && current.getBlockZ() == state.last.getBlockZ();
            boolean rotation = sameWorld && current.getPitch() == state.last.getPitch() && current.getYaw() == state.last.getYaw();
            if (!afkByMode(position, rotation, number(AFK, "check-mode", 1, 0, 3))) { state.idleSince = now; restoreAfk(player, state, true); }
            else {
                long seconds = Math.max(0, (now - state.idleSince) / 1_000_000_000L);
                if (cfg(AFK).getBoolean("kick.enabled", false) && seconds >= number(AFK, "kick.after-seconds", 1800, 60, 86400)) {
                    restoreAfk(player, state, false); player.kick(Component.text("长时间未活动")); c.count(AFK);
                } else if (cfg(AFK).getBoolean("teleport.enabled", false) && state.back == null && seconds >= number(AFK, "teleport.after-seconds", 600, 60, 86400)) {
                    World target = Bukkit.getWorld(cfg(AFK).getString("teleport.world", ""));
                    if (target != null) {
                        Location destination = new Location(target, cfg(AFK).getDouble("teleport.x"), cfg(AFK).getDouble("teleport.y", 80), cfg(AFK).getDouble("teleport.z"));
                        if (target.isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4) && player.teleport(destination)) { state.back = current.clone(); current = player.getLocation(); c.count(AFK); }
                    }
                } else if (cfg(AFK).getBoolean("hide-entities.enabled", false) && seconds >= number(AFK, "hide-entities.after-seconds", 300, 30, 86400)) hideNearby(player, state);
            }
            state.last = current;
        }
    }
    private void hideNearby(Player player, AfkState state) {
        int radius = number(AFK, "hide-entities.radius", 32, 1, 64), max = number(AFK, "hide-entities.max-per-player", 128, 1, 512);
        Plugin plugin = LBR.get().plugin();
        // Prune dead/unloaded entries so the bounded set can process newly arriving entities.
        state.hidden.removeIf(id -> Bukkit.getEntity(id) == null);
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (state.hidden.size() >= max) break;
            if (entity.equals(player) || entity instanceof Player && !cfg(AFK).getBoolean("hide-entities.include-players", false) || !player.canSee(entity)) continue;
            if (state.hidden.add(entity.getUniqueId())) { player.hideEntity(plugin, entity); c.count(AFK); }
        }
    }
    private void restoreAfk(Player player, AfkState state, boolean teleportBack) {
        if (state == null) return;
        Plugin plugin = LBR.get().plugin();
        for (UUID id : state.hidden) { Entity entity = Bukkit.getEntity(id); if (entity != null) player.showEntity(plugin, entity); }
        state.hidden.clear();
        if (teleportBack && state.back != null && cfg(AFK).getBoolean("teleport.return-on-activity", true)) {
            Location back = state.back; state.back = null;
            if (back.getWorld() != null && back.getWorld().isChunkLoaded(back.getBlockX() >> 4, back.getBlockZ() >> 4)) player.teleport(back);
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer(); restoreAfk(player, afk.remove(player.getUniqueId()), false); restoreCooldown(player);
        Inventory inv = trash.remove(player.getUniqueId()); if (inv != null) { closeViewers(inv); returnContents(player, inv); }
        for (AfkState state : afk.values()) state.hidden.remove(player.getUniqueId());
    }
}
