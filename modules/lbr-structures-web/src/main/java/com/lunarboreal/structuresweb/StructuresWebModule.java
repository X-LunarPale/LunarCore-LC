package com.lunarboreal.structuresweb;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebPanel;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only management is reloadable. World generation always belongs to LunarCoreStructures. */
public final class StructuresWebModule extends LbrModule implements Listener {
    private static final String PLUGIN = "LunarCoreStructures";
    private static final String PERMISSION = "core.server.manage";
    private final StructuresBridge bridge = new StructuresBridge();
    private final AtomicBoolean restartQueued = new AtomicBoolean();
    private volatile boolean running;

    @Override public void onEnable() {
        running = true;
        registerListener(this);
        bind();
        webRoutes(routes -> routes
                .get("/status", PERMISSION, c -> c.json(c.sync(() -> {
                    requireRunning();
                    return bridge.connected() ? call(bridge::status)
                            : Map.of("available", false, "message", bridge.problem());
                })))
                .get("/config", PERMISSION, c -> c.json(call(bridge::readConfig)))
                .post("/config", PERMISSION, c -> {
                    var saved = call(() -> bridge.saveConfig(c.bodyMap()));
                    audit("web:" + c.user().username(), "configure", "saved=" + saved.get("saved"));
                    c.json(saved);
                })
                .post("/restart", PERMISSION, c -> c.json(c.sync(() -> {
                    var status = call(bridge::status);
                    if (!Boolean.TRUE.equals(status.get("restartRequired")))
                        throw HttpException.badRequest("没有等待生效的结构设置");
                    if (!restartQueued.compareAndSet(false, true))
                        throw HttpException.badRequest("服务器重启已安排");
                    Object connection = bridge.connection();
                    try {
                        audit("web:" + c.user().username(), "restart", "saved=" + status.get("saved"));
                        runTaskLater(() -> {
                            try {
                                if (running && bridge.isCurrent(connection)
                                        && Boolean.TRUE.equals(call(bridge::status).get("restartRequired")))
                                    Bukkit.spigot().restart();
                            } finally { restartQueued.set(false); }
                        }, 40);
                    } catch (RuntimeException error) {
                        restartQueued.set(false);
                        throw error;
                    }
                    return Map.of("ok", true, "canRestart", Boolean.TRUE.equals(status.get("canRestart")));
                }))));
        web().registerStatic(id(), moduleClassLoader(), "web", this);
        webPanel(WebPanel.of("structures", "结构美化").icon("castle").group("服务器")
                .permission(PERMISSION).order(650)
                .section(new WebPanel.Iframe("", "/ext/structures/index.html").height(940)));
    }

    private void bind() {
        var plugin = Bukkit.getPluginManager().getPlugin(PLUGIN);
        bridge.bind(plugin != null && plugin.isEnabled() ? plugin : null);
    }

    @Override public void onReload() { if (running) bind(); }
    @Override public void onDisable() {
        running = false;
        bridge.disconnect();
        restartQueued.set(false);
        web().unregisterAll(this);
    }

    @EventHandler public void pluginEnabled(PluginEnableEvent event) {
        if (running && PLUGIN.equals(event.getPlugin().getName())) bind();
    }
    @EventHandler public void pluginDisabled(PluginDisableEvent event) {
        if (PLUGIN.equals(event.getPlugin().getName())) bridge.disconnect();
    }

    private void requireRunning() {
        if (!running) throw new HttpException(503, "结构管理模块已停用");
    }
    @FunctionalInterface private interface Operation { Map<String, Object> run() throws IOException; }
    private Map<String, Object> call(Operation operation) {
        requireRunning();
        try { return operation.run(); }
        catch (IllegalArgumentException e) { throw HttpException.badRequest(e.getMessage()); }
        catch (IllegalStateException e) { throw new HttpException(503, e.getMessage()); }
        catch (IOException e) {
            logger().warning("Structure configuration I/O failed: " + e.getClass().getSimpleName());
            throw new HttpException(500, "结构配置读写失败，请检查服务器日志");
        }
    }
}
