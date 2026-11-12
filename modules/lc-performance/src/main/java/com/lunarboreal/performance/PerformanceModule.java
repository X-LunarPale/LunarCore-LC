package com.lunarboreal.performance;

import com.lunarboreal.api.module.LcModule;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;

public final class PerformanceModule extends LcModule {
    private volatile PerformanceSettings settings;
    private DropCleaner cleaner;
    private BukkitTask samplingTask;
    private BukkitTask cleanerTask;
    private volatile boolean active;
    private volatile Map<String, Object> snapshot = Map.of("sampledAt", 0L, "worlds", List.of());
    private final LagFixerBridge bridge = new LagFixerBridge();
    private com.lunarboreal.performance.lagfixer.EmbeddedLagFixer embedded;

    @Override public void onEnable() {
        settings = PerformanceSettings.read(config());
        cleaner = new DropCleaner(() -> settings);
        registerListener(cleaner);
        active = true;
        embedded = new com.lunarboreal.performance.lagfixer.EmbeddedLagFixer(this);
        embedded.start();
        registerOptimizerCommands();
        new PerformanceWeb(this).register();
        restartTasks();
    }

    @Override public void onReload() {
        active = false;
        cancelTasks();
        embedded.close();
        reloadConfig();
        settings = PerformanceSettings.read(config());
        cleaner.clear();
        embedded.start();
        active = true;
        restartTasks();
    }

    @Override public void onDisable() {
        active = false;
        if (embedded != null) embedded.close();
        cancelTasks();
        if (cleaner != null) cleaner.clear();
        snapshot = Map.of("sampledAt", 0L, "worlds", List.of());
    }

    boolean active() { return active; }
    PerformanceSettings settings() { return settings; }
    Map<String, Object> snapshot() { return snapshot; }
    LagFixerBridge bridge() { return bridge; }
    com.lunarboreal.performance.lagfixer.EmbeddedLagFixer embedded() { return embedded; }

    private void registerOptimizerCommands() {
        registerCommand(new com.lunarboreal.api.command.LcCommand("lctrash", "lc.performance.trash") {
            @Override public void execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                if (!embedded.openTrash((org.bukkit.entity.Player) sender)) sender.sendMessage("垃圾箱未启用，或不允许在此世界使用。");
            }
        }.playerOnly(true).description("打开内置LagFixer垃圾箱"));
        registerCommand(new com.lunarboreal.api.command.LcCommand("lcabyss", "lc.performance.abyss") {
            @Override public void execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                if (!embedded.openAbyss((org.bukkit.entity.Player) sender)) sender.sendMessage("回收箱未启用或当前不可用。");
            }
        }.playerOnly(true).description("打开内置LagFixer有限掉落物回收箱"));
    }

    private void cancelTasks() {
        if (samplingTask != null) samplingTask.cancel();
        if (cleanerTask != null) cleanerTask.cancel();
        samplingTask = null;
        cleanerTask = null;
    }

    private void restartTasks() {
        cancelTasks();
        if (!active) return;
        sample();
        samplingTask = runTaskTimer(this::sample, settings.sampleSeconds() * 20L, settings.sampleSeconds() * 20L);
        if (settings.cleanerEnabled()) cleanerTask = runTaskTimer(cleaner::batch, settings.batchTicks(), settings.batchTicks());
    }

    private void sample() {
        if (!active) return;
        Runtime rt = Runtime.getRuntime();
        // Paper's counters avoid materializing loaded entity and chunk snapshots.
        List<Map<String, Object>> worlds = Bukkit.getWorlds().stream().map(w -> Map.<String, Object>of(
                "id", w.getUID().toString(), "name", w.getName(),
                "entities", w.getEntityCount(), "chunks", w.getChunkCount(),
                "players", w.getPlayers().size())).toList();
        snapshot = Map.of("sampledAt", System.currentTimeMillis(), "tps", Bukkit.getTPS()[0],
                "mspt", Bukkit.getAverageTickTime(), "memoryUsedBytes", rt.totalMemory() - rt.freeMemory(),
                "memoryMaxBytes", rt.maxMemory(), "worlds", worlds,
                "cleaner", Map.of("enabled", settings.cleanerEnabled(), "tracked", cleaner.tracked(),
                        "removed", cleaner.removed(), "overflow", cleaner.overflow()),
                "lagfixer", bridge.status());
    }
}
