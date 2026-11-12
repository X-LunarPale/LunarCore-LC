package com.lunarboreal.example.addon;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
import com.lunarboreal.api.web.WebRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Web 扩展示例：
 * <ol>
 *   <li>registerPermission —— 声明 Web 权限节点，管理员可在「角色管理」中勾选；</li>
 *   <li>routes —— REST 接口，挂载在 /api/ext/example/...，需登录并按权限节点校验；</li>
 *   <li>registerPanel —— 声明式面板（Stats / Table + Action / Form / Iframe / Text），前端自动渲染；</li>
 *   <li>registerStatic —— jar 内 web/ 目录映射到 /ext/example/...，配合 Iframe 面板嵌入完全自定义的页面；</li>
 *   <li>broadcast —— WebSocket 实时推送（见 ExampleCommand 与 web/index.html）。</li>
 * </ol>
 */
final class ExampleWeb {

    private final ExampleAddonPlugin p;

    ExampleWeb(ExampleAddonPlugin p) {
        this.p = p;
    }

    void register() {
        WebRegistry web = p.api().web();
        String ns = ExampleAddonPlugin.NS;

        // ---------- 1. 权限节点 ----------
        web.registerPermission("example.view", "查看示例扩展", p);
        web.registerPermission("example.manage", "管理示例扩展", p);

        // ---------- 2. REST 路由（处理器在 HTTP 线程执行） ----------
        web.routes(ns, p, r -> r
                .get("/stats", "example.view", this::stats)
                .get("/players", "example.view", this::players)
                .post("/players/{uuid}/add", "example.manage", this::addPoints)
                .post("/players/{uuid}/reset", "example.manage", this::reset)
                .post("/announce", "example.manage", this::announce)
                .get("/config", "example.manage", this::loadConfig)
                .post("/config", "example.manage", this::saveConfig)
                .get("/online", "example.view", this::online));

        // ---------- 3. 自定义静态页面：jar 内 web/index.html → /ext/example/index.html ----------
        web.registerStatic(ns, p.getClass().getClassLoader(), "web", p);

        // ---------- 4. 声明式面板 ----------
        WebPanel.Table table = new WebPanel.Table("玩家点数", "players")   // 相对路径 → /api/ext/example/players
                .rowKey("uuid")
                .pageSize(15)
                .column("uuid", "玩家", "player")
                .column(new WebPanel.Column("name", "名字", "text", true))
                .column(new WebPanel.Column("points", "点数", "number", true))
                .column("online", "在线", "bool")
                .rowAction(new WebPanel.Action("add", "加点", "players/{uuid}/add")
                        .style("primary").permission("example.manage")
                        .field(new WebPanel.Field("amount", "增加点数", "number").required(true).defaultValue(10)))
                .rowAction(new WebPanel.Action("reset", "清零", "players/{uuid}/reset")
                        .style("danger").permission("example.manage").confirm("确定清零该玩家的点数？"))
                .toolbarAction(new WebPanel.Action("announce", "游戏内广播", "announce")
                        .permission("example.manage")
                        .field(new WebPanel.Field("message", "广播内容", "textarea").required(true)
                                .placeholder("支持 MiniMessage，如 <gold>你好")));

        WebPanel.Form form = new WebPanel.Form("示例配置", "config", "config")
                .field(new WebPanel.Field("points_per_click", "每次点击点数", "number").required(true))
                .field(new WebPanel.Field("cooldown_seconds", "点击冷却（秒）", "number"))
                .field(new WebPanel.Field("announce_every", "广播间隔点数", "number").help("每达到该点数的整数倍时全服广播，0 关闭"))
                .span(12);

        web.registerPanel(ns, WebPanel.of("example", "示例扩展")
                .icon("puzzle")
                .group("扩展")
                .order(900)
                .permission("example.view")
                .section(new WebPanel.Stats("概览", "stats").refresh(10))
                .section(new WebPanel.Text("说明", """
                        这是 **LcExampleAddon** 注册的面板，演示外部插件如何扩展 LunarCore Web 管理界面。
                        游戏内输入 `/lcexample click` 增加点数，下方「实时动态」页面会通过 WebSocket 立即显示。
                        """))
                .section(table)
                .section(form)
                .section(new WebPanel.Iframe("实时动态（自定义页面）", "/ext/example/index.html").height(420)), p);
    }

    // =====================================================================

    private void stats(WebContext ctx) throws Exception {
        int online = ctx.sync(() -> Bukkit.getOnlinePlayers().size()); // Bukkit API 必须回主线程
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(stat("参与玩家", p.store().all().size(), "users"));
        out.add(stat("总点数", p.store().sum(), "mouse-pointer-click"));
        out.add(stat("在线人数", online, "activity"));
        ctx.json(out);
    }

    private static Map<String, Object> stat(String label, Object value, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", String.valueOf(value));
        m.put("icon", icon);
        return m;
    }

    /** Table 数据契约：?page=1&size=20&q=&sort=&order= → {"total":N,"items":[...]} */
    private void players(WebContext ctx) throws Exception {
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.max(1, Math.min(100, ctx.queryInt("size", 15)));
        boolean asc = "asc".equals(ctx.query("order", "desc"));
        List<ClickService.Entry> list = new ArrayList<>();
        for (ClickService.Entry e : p.store().all()) {
            if (q.isEmpty() || (e.name() != null && e.name().toLowerCase(Locale.ROOT).contains(q))) list.add(e);
        }
        Comparator<ClickService.Entry> cmp = "name".equals(ctx.query("sort", "points"))
                ? Comparator.comparing(e -> e.name() == null ? "" : e.name().toLowerCase(Locale.ROOT))
                : Comparator.comparingLong(ClickService.Entry::points);
        list.sort(asc ? cmp : cmp.reversed());
        List<UUID> onlineIds = ctx.sync(() -> Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        int from = Math.min(list.size(), (page - 1) * size);
        for (ClickService.Entry e : list.subList(from, Math.min(list.size(), from + size))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", e.uuid().toString());
            row.put("name", e.name());
            row.put("points", e.points());
            row.put("online", onlineIds.contains(e.uuid()));
            items.add(row);
        }
        ctx.page(list.size(), items);
    }

    private static String actor(WebContext ctx) {
        return "web:" + ctx.user().username();
    }

    private UUID uuid(WebContext ctx) {
        try {
            return UUID.fromString(ctx.pathParam("uuid"));
        } catch (Exception e) {
            throw HttpException.badRequest("UUID 无效");
        }
    }

    /** Action 契约：请求体 {"row":{...行数据...}, "amount": 10}；成功 {"ok":true,"message":"..."}，失败抛 HttpException。 */
    private void addPoints(WebContext ctx) {
        UUID id = uuid(ctx);
        Object raw = ctx.bodyMap().get("amount");
        long amount;
        try {
            amount = (long) Double.parseDouble(String.valueOf(raw)); // bodyMap 中数字为 Double
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("点数无效");
        }
        long now = p.store().add(id, null, amount);
        p.api().audit().log(ExampleAddonPlugin.NS, actor(ctx), "add", id + " +" + amount);
        ctx.ok("已增加，当前点数 " + now);
    }

    private void reset(WebContext ctx) {
        UUID id = uuid(ctx);
        p.store().reset(id);
        p.api().audit().log(ExampleAddonPlugin.NS, actor(ctx), "reset", id.toString());
        ctx.ok("已清零");
    }

    private void announce(WebContext ctx) throws Exception {
        Object m = ctx.bodyMap().get("message");
        if (m == null || String.valueOf(m).isBlank()) throw HttpException.badRequest("内容不能为空");
        String message = String.valueOf(m);
        ctx.sync(() -> {
            // 管理员输入视为可信 MiniMessage → rich()；若是玩家输入请直接传 String（core 会自动转义）
            var rich = com.lunarboreal.api.message.MessageService.rich(message);
            for (Player pl : Bukkit.getOnlinePlayers()) p.msg(pl, "web-message", "message", rich);
            return null;
        });
        p.api().audit().log(ExampleAddonPlugin.NS, actor(ctx), "announce", message);
        p.api().web().broadcast("example.announce", Map.of("by", ctx.user().username(), "message", message));
        ctx.ok("已广播");
    }

    /** Form 契约：GET → {字段: 值}；POST 请求体 {字段: 值}。 */
    private void loadConfig(WebContext ctx) {
        var c = p.getConfig();
        ctx.json(Map.of(
                "points_per_click", c.getLong("points-per-click"),
                "cooldown_seconds", c.getLong("cooldown-seconds"),
                "announce_every", c.getLong("announce-every")));
    }

    private void saveConfig(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        ctx.sync(() -> {
            var c = p.getConfig();
            c.set("points-per-click", num(body.get("points_per_click"), 1));
            c.set("cooldown-seconds", num(body.get("cooldown_seconds"), 2));
            c.set("announce-every", num(body.get("announce_every"), 100));
            p.saveConfig();
            return null;
        });
        p.api().audit().log(ExampleAddonPlugin.NS, actor(ctx), "config", body.toString());
        ctx.ok("已保存");
    }

    private static long num(Object v, long def) {
        try {
            return v == null ? def : (long) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 供自定义页面使用的接口（页面带 Authorization: Bearer <token> 调用）。 */
    private void online(WebContext ctx) throws Exception {
        List<String> names = ctx.sync(() -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        ctx.json(Map.of("online", names, "top", p.store().top(5)));
    }
}
