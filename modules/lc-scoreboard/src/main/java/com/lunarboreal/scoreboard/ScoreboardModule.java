package com.lunarboreal.scoreboard;

import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.api.web.*;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ScoreboardModule extends LcModule implements Listener {
    private ScoreboardSettings settings;
    private PlayerModes modes;
    private DisplayRenderer renderer;
    private BukkitTask task;
    private IslandPack pack;
    IslandPack pack() { return pack; }
    void refreshDisplays() { if (!closing && renderer != null) renderer.refresh(); }
    private boolean closing;
    ScoreboardSettings settings() { return settings; }
    ScoreboardSettings.Mode effective(Player player) {
        if (!settings.enabled() || !player.hasPermission("lc.scoreboard.use")) return ScoreboardSettings.Mode.OFF;
        var mode = modes.get(player.getUniqueId()); return mode == ScoreboardSettings.Mode.DEFAULT ? settings.defaultMode() : mode;
    }
    @Override public void onEnable() {
        settings = ScoreboardSettings.load(config()); modes = new PlayerModes(dataFolder().toPath().resolve("player-modes.tsv"));
        try { modes.load(); } catch (java.io.IOException ex) { throw new IllegalStateException("拒绝覆盖损坏的玩家显示偏好", ex); }
        renderer = new DisplayRenderer(this); defaultPermissions("lc.scoreboard.use");
        registerPermission("lc.scoreboard.admin-view", PermissionDefault.OP, "显示管理员专属计分板");
        registerPermission("lc.scoreboard.admin", PermissionDefault.OP, "重载计分板显示"); registerListener(this); registerCommand(new DisplayCommand());
        pack = new IslandPack(this); pack.start();
        api().web().registerStatic(id(), moduleClassLoader(), "web", this);
        restart(); registerWeb();
    }
    private void restart() {
        if (task != null) task.cancel(); renderer.close();
        if (settings.enabled() && !closing) { renderer.refresh(); task = runTaskTimer(renderer::refresh, settings.updateTicks(), settings.updateTicks()); }
    }
    @Override public void onReload() {
        reloadConfig(); ScoreboardSettings replacement = ScoreboardSettings.load(config()); settings = replacement; restart();
    }
    @Override public void onDisable() { closing = true; if (task != null) task.cancel(); if (renderer != null) renderer.close(); if (pack != null) pack.close(); }
    @EventHandler public void quit(PlayerQuitEvent event) { renderer.forget(event.getPlayer()); }
    private void save(ScoreboardSettings replacement) throws Exception {
        if (closing) throw new IllegalStateException("模块正在关闭");
        YamlConfiguration yaml = new YamlConfiguration(); replacement.apply(yaml);
        Path target = dataFolder().toPath().resolve("config.yml"); Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), "settings-", ".tmp");
        try { Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8); Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
        replacement.apply(config()); settings = replacement; restart();
    }
    private final class DisplayCommand extends LcCommand {
        DisplayCommand() { super("scoreboard", "lc.scoreboard.use", "sb"); description("切换计分板显示"); usage("/scoreboard sidebar|island|off|default|reload"); }
        @Override public void execute(CommandSender sender, String label, String[] args) {
            try {
                if (args.length != 1) throw new IllegalArgumentException("用法：/scoreboard sidebar|island|off|default|reload");
                if (args[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("lc.scoreboard.admin")) throw new IllegalArgumentException("没有管理权限");
                    onReload(); audit(sender.getName(), "reload", "configuration reloaded"); sender.sendMessage(Component.text("显示配置已重载")); return;
                }
                if (!(sender instanceof Player player)) throw new IllegalArgumentException("此操作需要玩家身份");
                if (args[0].equalsIgnoreCase("pack")) {
                    sender.sendMessage(Component.text(pack.offer(player) ? "资源包请求已发送；请在客户端选择是否加载" : "资源包当前不可用")); return;
                }
                var mode = ScoreboardSettings.Mode.parse(args[0]); modes.set(player.getUniqueId(), mode); renderer.forget(player); renderer.refresh();
                sender.sendMessage(Component.text("显示偏好已保存：" + mode.name().toLowerCase(Locale.ROOT)));
            } catch (Exception ex) { sender.sendMessage(Component.text("操作失败：" + ex.getMessage())); }
        }
        @Override public List<String> tabComplete(CommandSender sender, String label, String[] args) {
            return args.length == 1 ? filter(sender.hasPermission("lc.scoreboard.admin") ? List.of("sidebar", "island", "off", "default", "pack", "reload") : List.of("sidebar", "island", "off", "default", "pack"), args[0]) : List.of();
        }
    }
    private void registerWeb() {
        webPermission("scoreboard.view", "查看计分板设置与显示状态"); webPermission("scoreboard.manage", "调整计分板设置");
        webRoutes(routes -> {
            routes.get("/pack-info", "scoreboard.view", ctx -> ctx.json(ctx.sync(() -> pack.info())));
            routes.get("/pack-settings", "scoreboard.view", ctx -> ctx.json(ctx.sync(() -> pack.values())));
            routes.get("/pack-status", "scoreboard.view", ctx -> {
                var rows = ctx.sync(() -> pack.status()); ctx.page(rows.size(), rows);
            });
            routes.post("/pack-settings", "scoreboard.manage", ctx -> {
                IslandPack.Settings replacement;
                try { replacement = IslandPack.Settings.from(ctx.bodyMap()); } catch (IllegalArgumentException ex) { throw HttpException.badRequest(ex.getMessage()); }
                String actor = ctx.user().username();
                ctx.sync(() -> { var before = pack.values(); pack.save(replacement); refreshDisplays(); audit("web:" + actor, "pack-settings", "before=" + before + "; after=" + pack.values()); return null; });
                ctx.ok("资源包设置已保存");
            });
            routes.get("/settings", "scoreboard.view", ctx -> ctx.json(ctx.sync(() -> settings.webValues())));
            routes.get("/status", "scoreboard.view", ctx -> ctx.json(ctx.sync(() -> renderer.status())));
            routes.post("/settings", "scoreboard.manage", ctx -> {
                ScoreboardSettings replacement;
                try { replacement = ScoreboardSettings.from(ctx.bodyMap()); } catch (IllegalArgumentException ex) { throw HttpException.badRequest(ex.getMessage()); }
                String actor = ctx.user().username();
                ctx.sync(() -> { var before = settings.webValues(); save(replacement); audit("web:" + actor, "settings", "before=" + before + "; after=" + settings.webValues()); return null; });
                ctx.ok("显示设置已保存");
            });
        });
        var form = new WebPanel.Form("显示设置", "settings", "settings").savePermission("scoreboard.manage")
            .field(new WebPanel.Field("enabled", "启用显示", "bool"))
            .field(new WebPanel.Field("defaultMode", "默认模式", "select").option("sidebar", "右侧计分板").option("island", "顶部信息岛").option("off", "关闭").required(true))
            .field(new WebPanel.Field("title", "侧边栏标题", "text").required(true))
            .field(new WebPanel.Field("lines", "侧边栏内容", "textarea").help("最多15行，可保留空行"))
            .field(new WebPanel.Field("adminEnabled", "启用管理员专属计分板", "bool").help("拥有管理员计分板权限的玩家自动使用，默认管理员可见"))
            .field(new WebPanel.Field("adminTitle", "管理员计分板标题", "text").required(true))
            .field(new WebPanel.Field("adminLines", "管理员计分板内容", "textarea").help("最多15行，可显示在线人数、性能、实体和内存"))
            .field(new WebPanel.Field("frames", "顶部轮播内容", "textarea").help("每行一帧，1–100帧").required(true))
            .field(new WebPanel.Field("updateTicks", "刷新间隔（tick）", "number").help("10–1200 tick；20 tick约1秒").required(true))
            .field(new WebPanel.Field("rotate", "启用轮播", "bool"))
            .field(new WebPanel.Field("rotationTicks", "轮播间隔（tick）", "number").help("20–72000 tick").required(true))
            .field(new WebPanel.Field("progress", "顶部进度（0–1）", "number").required(true));
        var color = new WebPanel.Field("color", "顶部颜色", "select").required(true);
        for (var value : net.kyori.adventure.bossbar.BossBar.Color.values()) color.option(value.name(), switch (value) { case PINK -> "粉色"; case BLUE -> "蓝色"; case RED -> "红色"; case GREEN -> "绿色"; case YELLOW -> "黄色"; case PURPLE -> "紫色"; case WHITE -> "白色"; }); form.field(color);
        webPanel(WebPanel.of("scoreboard", "计分板显示").icon("layout-dashboard").group("玩家").permission("scoreboard.view")
            .section(new WebPanel.Text("显示说明", "可选择右侧计分板或顶部信息岛。加载可选资源包后显示胶囊，未加载时回退原版信息条；玩家可用 /scoreboard pack 请求加载。玩家使用 /scoreboard 选择个人模式。变量：{player} 玩家、{title} 当前称号、{balance} 余额、{world} 世界、{online} 在线、{ping} 延迟、{tps}、{mspt}。管理员还可使用 {entities} 实体数、{chunks} 区块数、{memory_used} 已用内存、{memory_max} 最大内存、{max_players} 人数上限。"))
            .section(new WebPanel.Stats("显示状态", "status").refresh(10)).section(form)
            .section(new WebPanel.Form("胶囊资源包", "pack-settings", "pack-settings").savePermission("scoreboard.manage")
                .field(new WebPanel.Field("enabled", "启用资源包", "bool"))
                .field(new WebPanel.Field("autoOffer", "加入时自动请求", "bool"))
                .field(new WebPanel.Field("url", "资源包直链", "text").help("客户端需可访问；必须与内置摘要对应").required(true)))
            .section(new WebPanel.Table("客户端资源包状态", "pack-status").rowKey("uuid").refresh(10).column("name", "玩家").column("status", "加载状态").column("loaded", "胶囊可用", "bool")));
    }
}
