/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Derived from LagFixer LagShieldModule by lychee (xyz.lychee.lagfixer.modules).
 * Upstream: https://modrinth.com/plugin/lagfixer
 * Source retained in third-party/lagfixer/upstream; see its GPLv3 LICENSE.
 * LC adaptation: synchronous TPS hysteresis, bounded loaded-world state, and
 * ownership-aware view/simulation distance restoration. Unlike upstream, this
 * port does not force mob AI, RANDOM_TICK_SPEED, explosions, decay or fireworks.
 */
package com.lunarboreal.performance.lagfixer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Optional LagShield port, id {@code lag-shield}, disabled by default.
 * Configuration: threshold-tps=16, recovery-tps=18, pause-spawns/hoppers/
 * redstone/liquids/projectiles=true, dynamic-distances=false, low-view=4,
 * low-simulation=3. All lifecycle calls must run on the server thread.
 * Existing cancelled events remain cancelled; redstone suppresses positive current
 * as in upstream, never re-enabling a signal suppressed by another listener.
 */
public final class LoadShield implements Listener, AutoCloseable {
    private static final String ID = "lag-shield";
    private final PortContext context;
    private final Map<UUID, Distances> distances = new HashMap<>();
    private BukkitTask task;
    private boolean running;
    private boolean shielding;

    private static final class Distances {
        final int originalView, originalSimulation;
        int appliedView, appliedSimulation;
        boolean ownsView = true, ownsSimulation = true;
        Distances(World world) {
            originalView = appliedView = world.getViewDistance();
            originalSimulation = appliedSimulation = world.getSimulationDistance();
        }
    }

    public LoadShield(PortContext context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
    }

    public void start() {
        if (running) return;
        running = true;
        context.listen(this);
        task = context.timer(this::sample, 20L, 20L);
    }

    private boolean enabled() {
        ConfigurationSection section = context.section(ID);
        return running && section != null && section.getBoolean("enabled", false) && context.enabled(ID);
    }

    private boolean pauses(World world, String flag) {
        ConfigurationSection section = context.section(ID);
        return shielding && enabled() && section.getBoolean(flag, true) && context.allows(ID, world);
    }

    private static double threshold(ConfigurationSection section, String key, double fallback) {
        double value = section.getDouble(key, fallback);
        return Double.isFinite(value) ? Math.max(0, Math.min(20, value)) : fallback;
    }

    private void sample() {
        if (!enabled()) {
            shielding = false;
            restoreAll();
            return;
        }
        ConfigurationSection section = context.section(ID);
        double enter = threshold(section, "threshold-tps", 16);
        double recover = Math.max(enter, threshold(section, "recovery-tps", 18));
        double tps = Bukkit.getTPS()[0];
        if (!Double.isFinite(tps)) return;
        if (!shielding && tps < enter) shielding = true;
        else if (shielding && tps >= recover) shielding = false;

        if (!shielding || !section.getBoolean("dynamic-distances", false)) {
            restoreAll();
            return;
        }
        int view = Math.max(2, Math.min(32, section.getInt("low-view", 4)));
        int simulation = Math.max(2, Math.min(32, section.getInt("low-simulation", 3)));
        Set<UUID> allowed = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            if (!context.allows(ID, world)) continue;
            UUID id = world.getUID();
            allowed.add(id);
            Distances saved = distances.computeIfAbsent(id, ignored -> new Distances(world));
            // Once another plugin/admin changes a field, relinquish it for this episode.
            // Do not reapply every second and fight a new administrator setting.
            if (saved.ownsView && world.getViewDistance() != saved.appliedView) saved.ownsView = false;
            if (saved.ownsSimulation && world.getSimulationDistance() != saved.appliedSimulation)
                saved.ownsSimulation = false;
            int targetView = Math.min(saved.originalView, view);
            int targetSimulation = Math.min(saved.originalSimulation, Math.min(simulation, targetView));
            if (saved.ownsView && saved.appliedView != targetView) {
                world.setViewDistance(targetView);
                saved.appliedView = targetView;
                context.count(ID);
            }
            if (saved.ownsSimulation && saved.appliedSimulation != targetSimulation) {
                world.setSimulationDistance(targetSimulation);
                saved.appliedSimulation = targetSimulation;
                context.count(ID);
            }
        }
        // UUID-only state is bounded by currently allowed loaded worlds, with no world/chunk references.
        for (UUID id : Set.copyOf(distances.keySet())) if (!allowed.contains(id)) {
            Distances saved = distances.remove(id);
            World world = Bukkit.getWorld(id);
            if (world != null) restore(world, saved);
        }
    }

    private void restore(World world, Distances saved) {
        if (saved.ownsView && world.getViewDistance() == saved.appliedView
                && saved.appliedView != saved.originalView) world.setViewDistance(saved.originalView);
        if (saved.ownsSimulation && world.getSimulationDistance() == saved.appliedSimulation
                && saved.appliedSimulation != saved.originalSimulation)
            world.setSimulationDistance(saved.originalSimulation);
    }

    private void restoreAll() {
        for (var entry : distances.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world != null) restore(world, entry.getValue());
        }
        distances.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        if (pauses(event.getEntity().getWorld(), "pause-spawns")) {
            event.setCancelled(true);
            context.count(ID);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void vehicle(VehicleCreateEvent event) {
        if (pauses(event.getVehicle().getWorld(), "pause-spawns")) {
            event.setCancelled(true);
            context.count(ID);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopper(InventoryMoveItemEvent event) {
        if (event.getSource().getType() != InventoryType.HOPPER
                && event.getDestination().getType() != InventoryType.HOPPER
                && event.getInitiator().getType() != InventoryType.HOPPER) return;
        Location location = event.getInitiator().getLocation();
        // A virtual inventory with no world location cannot safely pass the world filter.
        if (location != null && location.getWorld() != null && pauses(location.getWorld(), "pause-hoppers")) {
            event.setCancelled(true);
            context.count(ID);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void redstone(BlockRedstoneEvent event) {
        if (event.getNewCurrent() > 0 && pauses(event.getBlock().getWorld(), "pause-redstone")) {
            event.setNewCurrent(0);
            context.count(ID);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void liquid(BlockFromToEvent event) {
        if (event.getBlock().isLiquid() && pauses(event.getBlock().getWorld(), "pause-liquids")) {
            event.setCancelled(true);
            context.count(ID);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void projectile(ProjectileLaunchEvent event) {
        if (pauses(event.getEntity().getWorld(), "pause-projectiles")) {
            event.setCancelled(true);
            context.count(ID);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(WorldUnloadEvent event) {
        Distances saved = distances.remove(event.getWorld().getUID());
        if (saved != null) restore(event.getWorld(), saved);
    }

    @Override
    public void close() {
        running = false;
        shielding = false;
        if (task != null) { task.cancel(); task = null; }
        HandlerList.unregisterAll(this);
        restoreAll();
    }
}
