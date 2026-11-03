package com.lunarboreal.anticheat.web;

import com.lunarboreal.anticheat.AntiCheatModule;
import com.lunarboreal.anticheat.RecentFlags;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import org.bukkit.Bukkit;

/** Grim snapshots, scoped configuration editing and bridge administration. */
public final class AcWeb {
    private static final String VIEW = "anticheat.view", MANAGE = "anticheat.manage";
    private final AntiCheatModule m;
    private final GrimCommandRunner commands;
    public AcWeb(AntiCheatModule module) { m = module; commands = new GrimCommandRunner(module); }
    public void register() {
        m.webPermission(VIEW, "查看反作弊状态与记录");
        m.webPermission(MANAGE, "管理反作弊配置与操作");
        m.webRoutes(r -> r.get("/health", VIEW, this::health)
                .get("/commands", VIEW, this::commandCatalog)
                .post("/commands/run", VIEW, this::runCommand)
                .get("/commands/runs/{id}", VIEW, this::commandResult)
                .get("/stats", VIEW, this::stats)
                .get("/violations", VIEW, this::recent)
                .get("/alerts", VIEW, this::recent)
                .get("/settings", VIEW, this::settings)
                .post("/settings", MANAGE, this::saveSettings)
                .get("/grim/config", MANAGE, this::configDirectory)
                .get("/grim/config/{fileId}", MANAGE, this::configFile)
                .post("/grim/config/{fileId}", MANAGE, this::saveConfigFile)
                .post("/grim/reload", MANAGE, this::reloadGrim));
        m.webPanel(WebPanel.of("anticheat", "反作弊").icon("shield-alert").group("安全").order(30).permission(VIEW)
                .section(new WebPanel.Stats("GrimAC 运行状态", "stats").refresh(5))
                .section(new WebPanel.Table("近期违规记录", "violations")
                        .column("time", "时间", "datetime")
                        .column("player", "玩家", "player")
                        .column("check", "检测", "tag")
                        .column("vl", "违规等级", "number")
                        .column("detail", "详情")
                        .column("ping", "延迟（毫秒）", "number")
                        .column("world", "世界")
                        .pageSize(20)
                        .toolbarAction(new WebPanel.Action("reload-grim", "重载 GrimAC 配置", "grim/reload")
                                .permission(MANAGE).confirm("确定重载 GrimAC 配置？检测与处罚配置将按照 GrimAC 的设置重新载入。")))
                .section(new WebPanel.Form("桥接设置", "settings", "settings").savePermission(MANAGE)
                        .field(new WebPanel.Field("bridge.capture-enabled", "采集近期违规记录", "bool")
                                .help("关闭后停止新增后台记录。检测与处罚始终由 GrimAC 管理。"))
                        .field(new WebPanel.Field("bridge.recent-capacity", "近期记录上限", "number")
                                .help("允许范围：1 至 10000 条。记录保存在内存中，模块停止时清空。"))));
    }
    private void health(WebContext ctx) {
        ctx.json(Map.of("backend", "GrimAC", "connected", m.bridge().connected(), "status", m.bridge().status(),
                "version", m.bridge().version(), "captureEnabled", m.captureEnabled(), "cached", m.recent().size(),
                "capacity", m.recent().capacity(), "captured", m.recent().captured(), "captureFailures", m.captureFailures(),
                "reloadStatus", m.bridge().reloadStatus()));
    }
    private void commandCatalog(WebContext ctx) {
        ctx.json(Map.of("artifact", GrimCommands.ARTIFACT, "commands", GrimCommands.CATALOG));
    }
    private void runCommand(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        if (body.keySet().stream().anyMatch(k -> !k.equals("id") && !k.equals("args"))) throw HttpException.badRequest("存在未声明的字段");
        if (!(body.get("id") instanceof String id)) throw HttpException.badRequest("缺少操作标识");
        GrimCommands.Op op = GrimCommands.find(id);
        if (ctx.user() == null || !ctx.user().hasPermission(op.permission())) {
            m.audit(actor(ctx), "grim-command-denied", id);
            throw HttpException.forbidden("没有执行此操作的权限");
        }
        Object rawArgs = body.getOrDefault("args", Map.of());
        if (!(rawArgs instanceof Map<?, ?>)) throw HttpException.badRequest("args 必须为对象");
        @SuppressWarnings("unchecked") Map<String, Object> args = (Map<String, Object>) rawArgs;
        String command;
        try { command = GrimCommands.build(op, args); }
        catch (HttpException e) { m.audit(actor(ctx), "grim-command-rejected", id + " " + e.getMessage()); throw e; }
        try { ctx.json(ctx.sync(() -> commands.run(actor(ctx), op, command)).json()); }
        catch (IllegalStateException e) { throw HttpException.badRequest(e.getMessage()); }
    }
    private void commandResult(WebContext ctx) {
        GrimCommandRunner.Run run = commands.find(ctx.pathParam("id"), actor(ctx));
        if (run == null) throw HttpException.notFound("执行记录不存在或已过期");
        if (ctx.user() == null || !ctx.user().hasPermission(commands.permission(run))) throw HttpException.forbidden("没有查看此结果的权限");
        ctx.json(run.json());
    }
    private void stats(WebContext ctx) {
        ctx.json(List.of(stat("检测后端", "GrimAC"), stat("连接状态", m.bridge().status()),
                stat("后端版本", m.bridge().version()), stat("违规采集", m.captureEnabled() ? "开启" : "关闭"),
                stat("已缓存记录", m.recent().size() + " / " + m.recent().capacity()),
                stat("累计采集", m.recent().captured()), stat("读取失败", m.captureFailures()),
                stat("配置重载", m.bridge().reloadStatus())));
    }
    private static Map<String, Object> stat(String label, Object value) {
        return Map.of("label", label, "value", String.valueOf(value), "icon", "shield-alert");
    }
    private void recent(WebContext ctx) {
        List<RecentFlags.Flag> flags = m.recent().snapshot(ctx.query("q", ""));
        long page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        int from = (int) Math.min(flags.size(), (page - 1L) * size);
        ctx.page(flags.size(), flags.subList(from, Math.min(flags.size(), from + size)).stream().map(RecentFlags.Flag::toMap).toList());
    }
    private void settings(WebContext ctx) {
        ctx.json(Map.of("bridge.capture-enabled", m.captureEnabled(), "bridge.recent-capacity", m.recent().capacity()));
    }
    private void saveSettings(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        if (body.containsKey("bridge.capture-enabled") && !(body.get("bridge.capture-enabled") instanceof Boolean))
            throw HttpException.badRequest("采集开关必须为布尔值");
        Integer capacity = null;
        if (body.containsKey("bridge.recent-capacity")) {
            try {
                double value = Double.parseDouble(String.valueOf(body.get("bridge.recent-capacity")));
                if (!Double.isFinite(value) || value != Math.rint(value) || value < 1 || value > RecentFlags.MAX_CAPACITY)
                    throw new NumberFormatException();
                capacity = (int) value;
            } catch (NumberFormatException e) { throw HttpException.badRequest("近期记录上限必须为 1 至 10000 的整数"); }
        }
        Integer cap = capacity;
        ctx.sync(() -> {
            if (body.containsKey("bridge.capture-enabled")) m.config().set("bridge.capture-enabled", body.get("bridge.capture-enabled"));
            if (cap != null) m.config().set("bridge.recent-capacity", cap);
            m.saveConfig(); m.onReload(); return null;
        });
        m.audit(actor(ctx), "bridge-settings", "采集=" + m.captureEnabled() + " 上限=" + m.recent().capacity());
        ctx.ok("桥接设置已保存");
    }
    private void reloadGrim(WebContext ctx) throws Exception {
        String actor = actor(ctx);
        try {
            ctx.sync(() -> {
                m.audit(actor, "grim-reload-request", "请求重载 GrimAC 配置");
                m.bridge().reload(result -> m.audit(actor, "grim-reload-result", result));
                return null;
            });
        } catch (IllegalStateException e) { throw HttpException.badRequest(e.getMessage()); }
        ctx.ok("已提交 GrimAC 配置重载，请查看运行状态中的重载结果");
    }
    private GrimConfigStore configStore(WebContext ctx) throws Exception {
        Path folder = ctx.sync(() -> {
            var plugin = Bukkit.getPluginManager().getPlugin("GrimAC");
            if (plugin == null) throw HttpException.notFound("GrimAC 未安装，无法读取配置文件");
            return plugin.getDataFolder().toPath();
        });
        return new GrimConfigStore(folder);
    }
    private void configDirectory(WebContext ctx) throws Exception {
        ctx.json(configStore(ctx).directory());
    }
    private void configFile(WebContext ctx) throws Exception {
        ctx.json(configStore(ctx).read(ctx.pathParam("fileId")));
    }
    private void saveConfigFile(WebContext ctx) throws Exception {
        String id = ctx.pathParam("fileId");
        Map<String, Object> result = configStore(ctx).patch(id, ctx.bodyMap());
        m.audit(actor(ctx), "grim-config-save", id + " keys=" + result.get("changedKeys"));
        ctx.json(result);
    }
    private static String actor(WebContext ctx) { return "web:" + (ctx.user() == null ? "?" : ctx.user().username()); }
}
