package com.lunarboreal.motd;

import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebPanel;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

public final class MotdModule extends LcModule implements Listener {
    private record Snapshot(MotdSettings settings, MotdTemplate normal, MotdTemplate maintenance,
                            MotdTemplate joinMessage, MotdTemplate kickMessage, MaintenancePolicy policy, String version) {
        MotdTemplate activeTemplate() { return settings.maintenance().enabled() ? maintenance : normal; }
    }
    private record PingCache(Snapshot owner, MotdTemplate template) {}
    private volatile Snapshot snapshot;
    private volatile PingCache pingCache;
    private boolean closing;

    /** Main-thread refresh only; no Bukkit or expansion access from asynchronous ping handlers. */
    private void refreshPingCache() {
        Snapshot current = snapshot;
        if (current == null) return;
        MotdTemplate active = resolve(current.activeTemplate(), null);
        pingCache = new PingCache(current, active);
    }

    private MotdTemplate resolve(MotdTemplate template, Player player) {
        if (!template.hasPlaceholders() || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return template;
        try { return template.resolvePlaceholders(token -> api().placeholders().apply(player, token)); }
        catch (RuntimeException | LinkageError ex) {
            // Preserve configured source if an expansion fails; never break login or server-list requests.
            return template;
        }
    }

    private MotdTemplate pingTemplate(Snapshot current) {
        PingCache cache = pingCache;
        return cache != null && cache.owner() == current ? cache.template() : current.activeTemplate();
    }

    @Override public void onEnable() {
        closing = false;
        snapshot = createSnapshot(MotdSettings.load(config()));
        refreshPingCache();
        runTaskTimer(this::refreshPingCache, 20L, 20L);
        registerListener(this);
        registerWeb();
    }

    private Snapshot createSnapshot(MotdSettings settings) {
        MaintenanceSettings maintenance = settings.maintenance();
        return new Snapshot(settings, new MotdTemplate(settings), new MotdTemplate(maintenance.line1(), maintenance.line2()),
                new MotdTemplate(maintenance.joinMessage()), new MotdTemplate(maintenance.kickMessage()),
                new MaintenancePolicy(maintenance), Bukkit.getMinecraftVersion());
    }

    @Override public void onReload() {
        reloadConfig();
        // Loading/reloading configuration never implicitly disconnects players.
        snapshot = createSnapshot(MotdSettings.load(config()));
        refreshPingCache();
    }

    @Override public void onDisable() { closing = true; snapshot = null; pingCache = null; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPing(ServerListPingEvent event) {
        Snapshot current = snapshot;
        if (current == null || !MaintenancePolicy.displaysMotd(current.settings())) return;
        event.motd(pingTemplate(current).render(event.getNumPlayers(), event.getMaxPlayers(), current.version()));
    }

    // PlayerLoginEvent is synchronous: inspect the final local identity without any async Bukkit player access.
    // Never allow a player or replace a refusal already issued by another plugin/the server.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        Snapshot current = snapshot;
        if (current == null) return;
        Player player = event.getPlayer();
        if (current.policy().rejectJoin(true, player.getName(), player.getUniqueId())) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    resolve(current.joinMessage(), player).render(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), current.version()));
        }
    }

    /** Called only through ctx.sync. Persist before publishing the replacement or executing any eviction. */
    private void save(MotdSettings settings) throws Exception {
        if (closing) throw new IllegalStateException("模块正在关闭");
        Snapshot replacement = createSnapshot(settings);
        YamlConfiguration yaml = new YamlConfiguration();
        // Retain unrelated/future configuration keys rather than rewriting only known fields.
        yaml.loadFromString(config().saveToString());
        yaml.options().copyDefaults(false);
        settings.apply(yaml);
        Path target = dataFolder().toPath().resolve("config.yml").toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "motd-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        settings.apply(config());
        snapshot = replacement;
        refreshPingCache();
    }

    /** One-shot action, not a repeating enforcement task. Other plugins may cancel PlayerKickEvent. */
    private int kickOnline(Snapshot current) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("维护踢出操作须在服务器主线程执行");
        List<? extends Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        int maxPlayers = Bukkit.getMaxPlayers();
        int requested = 0;
        for (Player player : players) {
            if (player.isOnline() && current.policy().kickOnRequest(player.getName(), player.getUniqueId())) {
                player.kick(resolve(current.kickMessage(), player).render(players.size(), maxPlayers, current.version()));
                requested++;
            }
        }
        return requested;
    }

    private Map<String, Object> maintenanceStatus() {
        Snapshot current = snapshot;
        return Map.of("id", "maintenance", "enabled", current.settings().maintenance().enabled(),
                "allowJoin", current.settings().maintenance().allowJoin(), "kickOnline", current.settings().maintenance().kickOnline(),
                "online", Bukkit.getOnlinePlayers().size(), "eligible", Bukkit.getOnlinePlayers().stream()
                        .filter(p -> current.policy().kickOnRequest(p.getName(), p.getUniqueId())).count());
    }

    private void registerWeb() {
        webPermission("motd.view", "查看服务器展示及维护设置");
        webPermission("motd.manage", "修改服务器展示及维护设置、执行维护踢出");
        webRoutes(routes -> {
            routes.get("/settings", "motd.view", ctx -> ctx.json(snapshot.settings().webValues()));
            routes.get("/maintenance", "motd.view", ctx -> ctx.json(snapshot.settings().maintenance().webValues()));
            routes.get("/maintenance/status", "motd.view", ctx -> ctx.json(ctx.sync(() -> List.of(maintenanceStatus()))));
            routes.get("/preview", "motd.view", ctx -> ctx.json(ctx.sync(() -> {
                Snapshot current = snapshot;
                String text = PlainTextComponentSerializer.plainText().serialize(pingTemplate(current)
                        .render(Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(), current.version()));
                return Map.of("enabled", MaintenancePolicy.displaysMotd(current.settings()),
                        "maintenance", current.settings().maintenance().enabled(), "text", text);
            })));
            routes.post("/settings", "motd.manage", ctx -> {
                MotdSettings replacement;
                try { replacement = MotdSettings.from(ctx.bodyMap()); }
                catch (IllegalArgumentException ex) { throw HttpException.badRequest(ex.getMessage()); }
                String actor = ctx.user().username();
                ctx.sync(() -> {
                    var before = snapshot.settings().webValues();
                    save(snapshot.settings().withNormal(replacement));
                    audit("web:" + actor, "settings", "before=" + before + "; after=" + replacement.webValues());
                    return null;
                });
                ctx.ok("正常服务器展示设置已保存");
            });
            routes.post("/maintenance", "motd.manage", ctx -> {
                MaintenanceSettings replacement;
                try { replacement = MaintenanceSettings.from(ctx.bodyMap()); }
                catch (IllegalArgumentException ex) { throw HttpException.badRequest(ex.getMessage()); }
                String actor = ctx.user().username();
                int requested = ctx.sync(() -> {
                    MaintenanceSettings before = snapshot.settings().maintenance();
                    save(snapshot.settings().withMaintenance(replacement));
                    boolean kick = MaintenancePolicy.kickAfterSave(before, replacement);
                    int count = kick ? kickOnline(snapshot) : 0;
                    audit("web:" + actor, "maintenance-settings", "before=" + before.webValues()
                            + "; after=" + replacement.webValues() + "; kickTriggered=" + kick + "; kickRequested=" + count);
                    return count;
                });
                ctx.ok("维护设置已保存；本次已向 " + requested + " 名非白名单在线玩家发起踢出请求");
            });
            routes.post("/maintenance/kick", "motd.manage", ctx -> {
                try { validateKickBody(ctx.bodyMap()); }
                catch (IllegalArgumentException ex) { throw HttpException.badRequest(ex.getMessage()); }
                String actor = ctx.user().username();
                int requested = ctx.sync(() -> {
                    Snapshot current = snapshot;
                    if (!current.settings().maintenance().enabled()) throw HttpException.badRequest("请先启用维护模式");
                    int count = kickOnline(current);
                    audit("web:" + actor, "maintenance-kick", "kickRequested=" + count);
                    return count;
                });
                ctx.ok("已向 " + requested + " 名非白名单在线玩家发起踢出请求");
            });
        });
        webPanel(WebPanel.of("motd", "服务器展示与维护").icon("server").group("系统").permission("motd.view")
                .section(new WebPanel.Text("展示说明", "设置 Minecraft 多人游戏服务器列表中的两行简介，保存后即时生效。正常展示与维护展示分别保存；维护期间始终使用维护简介，不受正常展示开关影响。支持 MiniMessage 颜色、渐变与文字样式，以及 {online}、{max}、{version} 变量。简介每行及提示内容最多 512 个字符，不支持换行。"))
                .section(new WebPanel.Form("正常服务器列表简介", "settings", "settings").savePermission("motd.manage")
                        .field(new WebPanel.Field("enabled", "启用正常服务器展示", "bool").help("关闭后，非维护期间使用服务器原有简介"))
                        .field(new WebPanel.Field("line1", "正常简介第一行", "text"))
                        .field(new WebPanel.Field("line2", "正常简介第二行", "text")))
                .section(new WebPanel.Text("维护执行规则", "允许加入与踢出在线玩家为独立选项。白名单玩家可通过维护入服检查，且不受维护踢出影响；其他服务器规则和插件限制仍然有效。保存时，仅在启用维护且勾选自动踢出，或维护期间首次开启自动踢出时执行一次踢出。修改简介、提示或白名单不会重复踢出；启动、重载也不会执行踢出。手动动作使用已保存的提示与白名单，在维护开启时可独立执行，不受自动踢出选项限制。踢出请求可能被其他插件取消。"))
                .section(new WebPanel.Form("维护模式设置", "maintenance", "maintenance").savePermission("motd.manage").submitLabel("保存维护设置")
                        .field(new WebPanel.Field("enabled", "启用维护模式", "bool"))
                        .field(new WebPanel.Field("allowJoin", "维护期间允许非白名单玩家加入", "bool"))
                        .field(new WebPanel.Field("kickOnline", "启用维护时踢出非白名单在线玩家", "bool").help("仅按维护执行规则发起一次踢出，不持续限制在线玩家"))
                        .field(new WebPanel.Field("whitelist", "维护白名单", "textarea").help("每行一个名称或 UUID，最多 500 项。名称为 3–16 位英文字母、数字或下划线，忽略大小写；UUID 可使用标准带连字符格式或 32 位十六进制格式。按服务器实际身份匹配，不查询外部账号。"))
                        .field(new WebPanel.Field("joinMessage", "维护入服拒绝提示", "text"))
                        .field(new WebPanel.Field("kickMessage", "维护踢出提示", "text"))
                        .field(new WebPanel.Field("line1", "维护简介第一行", "text"))
                        .field(new WebPanel.Field("line2", "维护简介第二行", "text")))
                .section(new WebPanel.Table("维护状态与操作", "maintenance/status").rowKey("id").searchable(false)
                        .column("enabled", "维护模式", "bool").column("allowJoin", "允许非白名单加入", "bool")
                        .column("kickOnline", "自动踢出选项", "bool").column("online", "在线人数", "number")
                        .column("eligible", "可发起维护踢出人数", "number")
                        .toolbarAction(new WebPanel.Action("maintenance-kick", "立即踢出非白名单在线玩家", "maintenance/kick")
                                .permission("motd.manage").style("danger")
                                .confirm("确认按照已保存的维护白名单及踢出提示，立即向所有非白名单在线玩家发起踢出？维护模式须已启用。"))));
    }

    static void validateKickBody(Map<String, Object> body) {
        if (body == null) return;
        if (body.size() == 1 && body.get("row") instanceof Map<?, ?> row && row.isEmpty()) return;
        throw new IllegalArgumentException("维护踢出操作无需额外参数");
    }
}
