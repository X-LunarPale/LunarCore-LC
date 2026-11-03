package com.lunarboreal.performance.lagfixer;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.performance.lagfixer.PortContext;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.logging.Logger;

/** Lifecycle host for the GPLv3 LagFixer ports; each optimizer owns its bounded state and tasks. */
public final class EmbeddedLagFixer implements PortContext, AutoCloseable {
    public static final List<String> IDS = List.of("lag-shield", "redstone", "explosion", "leaf-decay", "hopper", "entity-limiter", "world-cleaner", "trash-disposal", "ability-limiter", "afk", "mob-ai", "vehicle", "console-filter");
    private final LbrModule owner;
    private final Plugin plugin;
    private final Logger logger;
    private final Map<String, BukkitTask> tasks = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Long> operations = new HashMap<>();
    private boolean running;
    private long generation;
    private int sequence;
    private WorldOptimizations worlds;
    private EntityOptimizations entities;
    private NmsOptimizations nms;
    private LoadShield shield;
    private ConsoleOptimizer console;

    public EmbeddedLagFixer(LbrModule owner) {
        this.owner = owner;
        this.plugin = owner.plugin();
        this.logger = owner.logger();
    }

    public void start() {
        close();
        running = true;
        try {
            worlds = new WorldOptimizations(this); worlds.start();
            entities = new EntityOptimizations(this); entities.start();
            shield = new LoadShield(this); shield.start();
            console = new ConsoleOptimizer(this); console.start();
            nms = new NmsOptimizations(this); nms.start();
        } catch (RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    @Override public ConfigurationSection section(String id) { return owner.config().getConfigurationSection("embedded." + requireId(id)); }
    @Override public boolean enabled(String id) { return running && owner.config().getBoolean("embedded." + requireId(id) + ".enabled", false); }
    @Override public boolean allows(String id, World world) {
        if (world == null) return false;
        ConfigurationSection s = section(id);
        List<String> worlds = s == null ? List.of() : s.getStringList("worlds");
        return worlds.contains("*") || worlds.contains(world.getName());
    }
    @Override public void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }
    @Override public BukkitTask timer(Runnable action, long delay, long period) {
        String key = "task-" + (++sequence);
        tasks.values().removeIf(BukkitTask::isCancelled);
        BukkitTask task = owner.runTaskTimer(() -> {
            if (running) action.run();
            BukkitTask current = tasks.get(key);
            if (current != null && current.isCancelled()) tasks.remove(key);
        }, delay, period);
        tasks.put(key, task);
        return task;
    }
    @Override public void later(Runnable action, long ticks) {
        String key = "task-" + (++sequence);
        long currentGeneration = generation;
        BukkitTask task = owner.runTaskLater(() -> {
            tasks.remove(key);
            if (running && generation == currentGeneration) action.run();
        }, Math.max(1, ticks));
        tasks.put(key, task);
    }
    @Override public void count(String id) {
        if (id == null || !id.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid counter id");
        if (operations.size() < 64 || operations.containsKey(id)) operations.merge(id, 1L, Long::sum);
    }
    @Override public Logger logger() { return logger; }

    public List<Map<String,Object>> status() {
        List<Map<String,Object>> out = new ArrayList<>();
        for (String id : IDS) out.add(Map.of("id", id, "enabled", enabled(id), "operations", operations.getOrDefault(id, 0L)));
        return List.copyOf(out);
    }
    public static List<String> ids() { return IDS; }
    public void setEnabled(String id, boolean enabled) { owner.config().set("embedded." + requireId(id) + ".enabled", enabled); }
    @Override public void close() {
        running = false;
        generation++;
        closePart(console); console = null;
        closePart(nms); nms = null;
        closePart(shield); shield = null;
        closePart(entities); entities = null;
        closePart(worlds); worlds = null;
        for (BukkitTask task : tasks.values()) task.cancel();
        tasks.clear();
        for (Listener listener : listeners) HandlerList.unregisterAll(listener);
        listeners.clear();
    }
    public boolean openTrash(org.bukkit.entity.Player player) { return entities != null && entities.openTrash(player); }
    public boolean openAbyss(org.bukkit.entity.Player player) { return entities != null && entities.openAbyss(player); }
    private void closePart(AutoCloseable part) {
        if (part == null) return;
        try { part.close(); } catch (Exception e) { logger.log(java.util.logging.Level.SEVERE, "Restoring embedded optimizer failed", e); }
    }
    private static String requireId(String id) {
        if (!IDS.contains(id)) throw new IllegalArgumentException("Unknown embedded optimizer: " + id);
        return id;
    }
}
