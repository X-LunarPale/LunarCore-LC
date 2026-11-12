package com.lunarboreal.anticheat;

import com.lunarboreal.anticheat.web.AcWeb;
import com.lunarboreal.api.module.LcModule;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.PermissionDefault;

import java.util.concurrent.atomic.AtomicLong;

/** Optional GrimAC management bridge. Detection and punishment belong exclusively to GrimAC. */
public final class AntiCheatModule extends LcModule implements Listener {
    private final RecentFlags recent = new RecentFlags();
    private final AtomicLong captureFailures = new AtomicLong();
    private volatile boolean captureEnabled = true;
    private GrimBridge bridge;
    private volatile boolean running;

    @Override public void onEnable() {
        reloadConfig();
        applyConfig();
        running = true;
        bridge = new GrimBridge(this);
        registerListener(this);
        registerPermission("lc.anticheat.command", PermissionDefault.OP, "查看 GrimAC 桥接状态");
        registerPermission("lc.anticheat.reload", PermissionDefault.OP, "重载反作弊桥接配置");
        registerCommand(new AcCommand(this));
        new AcWeb(this).register();
        bind();
        // Plugin enable can precede Grim's API initialization; retry next tick once.
        runTaskLater(() -> { if (running && !bridge.connected()) bind(); }, 1L);
        placeholder((player, key) -> switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "backend" -> "GrimAC";
            case "connected" -> String.valueOf(bridge.connected());
            default -> null;
        });
    }
    @Override public void onDisable() {
        running = false;
        if (bridge != null) bridge.close();
        recent.clear();
    }
    @Override public void onReload() {
        reloadConfig();
        applyConfig();
        if (running) bind();
    }
    private void applyConfig() {
        captureEnabled = config().getBoolean("bridge.capture-enabled", true);
        recent.capacity(config().getInt("bridge.recent-capacity", 500));
    }
    private void bind() { bridge.bind(Bukkit.getPluginManager().getPlugin("GrimAC")); }
    @EventHandler public void pluginEnabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("GrimAC"))
            runTaskLater(() -> { if (running) bind(); }, 1L);
    }
    @EventHandler public void pluginDisabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("GrimAC")) bridge.close();
    }
    public boolean captureEnabled() { return captureEnabled; }
    public RecentFlags recent() { return recent; }
    public GrimBridge bridge() { return bridge; }
    public long captureFailures() { return captureFailures.get(); }
    void captureFailed() {
        if (captureFailures.incrementAndGet() == 1) logger().warning("无法读取 GrimAC 违规事件，请检查 API 版本兼容性");
    }
}
