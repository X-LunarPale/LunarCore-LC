package com.lunarboreal.essentials.web;

import com.lunarboreal.api.util.Durations;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
import com.lunarboreal.essentials.EssentialsModule;
import com.lunarboreal.essentials.commands.InfoCommands;
import com.lunarboreal.essentials.commands.ItemCommands;
import com.lunarboreal.essentials.commands.PlayerCommands;
import com.lunarboreal.essentials.feature.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Web 面板：在线玩家状态、礼包、价格表、配置。 */
public final class EssWeb {

    private static final String VIEW = "essentials.view";
    private static final String MANAGE = "essentials.manage";

    /** 网页表单可编辑的配置项：key → 类型 */
    private static final Map<String, String> CONFIG_FIELDS = new LinkedHashMap<>();

    static {
        CONFIG_FIELDS.put("override-vanilla", "bool");
        CONFIG_FIELDS.put("afk.auto-afk-seconds", "number");
        CONFIG_FIELDS.put("afk.kick-seconds", "number");
        CONFIG_FIELDS.put("afk.broadcast", "bool");
        CONFIG_FIELDS.put("afk.ignore-sleep", "bool");
        CONFIG_FIELDS.put("afk.invulnerable", "bool");
        CONFIG_FIELDS.put("vanish.hide-join-quit", "bool");
        CONFIG_FIELDS.put("vanish.allow-pickup", "bool");
        CONFIG_FIELDS.put("vanish.hide-from-server-list", "bool");
        CONFIG_FIELDS.put("vanish.fake-join-quit", "bool");
        CONFIG_FIELDS.put("speed.max", "number");
        CONFIG_FIELDS.put("give.max-amount", "number");
        CONFIG_FIELDS.put("give.default-amount", "number");
        CONFIG_FIELDS.put("enchant.allow-unsafe", "bool");
        CONFIG_FIELDS.put("enchant.max-unsafe-level", "number");
        CONFIG_FIELDS.put("repair.allow-enchanted", "bool");
        CONFIG_FIELDS.put("spawnmob.max", "number");
        CONFIG_FIELDS.put("near.default-radius", "number");
        CONFIG_FIELDS.put("near.max-radius", "number");
        CONFIG_FIELDS.put("color.signs", "bool");
        CONFIG_FIELDS.put("color.anvil", "bool");
        CONFIG_FIELDS.put("kits.first-join-enabled", "bool");
        CONFIG_FIELDS.put("kits.gui", "bool");
        CONFIG_FIELDS.put("worth.sell-modified-items", "bool");
        CONFIG_FIELDS.put("worth.allow-creative-sell", "bool");
        CONFIG_FIELDS.put("powertool.enabled", "bool");
    }

    private final EssentialsModule m;

    public EssWeb(EssentialsModule m) {
        this.m = m;
    }

    public void register() {
        m.webPermission(VIEW, "查看基础命令模块（在线玩家状态/礼包/价格）");
        m.webPermission(MANAGE, "管理基础命令模块（治疗/飞行/模式/礼包/价格/配置）");

        m.webRoutes(r -> r
                .get("/stats", VIEW, this::stats)
                .get("/players", VIEW, this::players)
                .post("/players/{uuid}/{action}", MANAGE, this::playerAction)
                .get("/kits", VIEW, this::kits)
                .post("/kits/{name}/update", MANAGE, this::kitUpdate)
                .post("/kits/{name}/delete", MANAGE, this::kitDelete)
                .get("/worth", VIEW, this::worth)
                .post("/worth/set", MANAGE, this::worthSet)
                .post("/worth/{item}/delete", MANAGE, this::worthDelete)
                .get("/config", MANAGE, this::configLoad)
                .post("/config", MANAGE, this::configSave));

        WebPanel.Field mode = new WebPanel.Field("mode", "游戏模式", "select")
                .option("SURVIVAL", "生存").option("CREATIVE", "创造").option("ADVENTURE", "冒险").option("SPECTATOR", "旁观")
                .required(true);

        WebPanel.Form form = new WebPanel.Form("模块配置", "config", "config");
        for (var e : CONFIG_FIELDS.entrySet()) form.field(new WebPanel.Field(e.getKey(), switch (e.getKey()) {
            case "override-vanilla" -> "接管同名原版命令";
            case "afk.auto-afk-seconds" -> "自动进入暂离状态（秒）";
            case "afk.kick-seconds" -> "暂离后自动踢出（秒）";
            case "afk.broadcast" -> "广播暂离状态变化";
            case "afk.ignore-sleep" -> "暂离玩家不参与睡眠人数统计";
            case "afk.invulnerable" -> "暂离玩家免受伤害";
            case "vanish.hide-join-quit" -> "隐藏隐身玩家的进出提示";
            case "vanish.allow-pickup" -> "允许隐身玩家拾取物品";
            case "vanish.hide-from-server-list" -> "在服务器列表隐藏隐身玩家";
            case "vanish.fake-join-quit" -> "切换隐身时模拟进出提示";
            case "speed.max" -> "速度设置上限";
            case "give.max-amount" -> "给予物品数量上限";
            case "give.default-amount" -> "默认给予物品数量";
            case "enchant.allow-unsafe" -> "允许超出原版限制的附魔";
            case "enchant.max-unsafe-level" -> "超限附魔等级上限";
            case "repair.allow-enchanted" -> "允许修复附魔物品";
            case "spawnmob.max" -> "单次生成生物数量上限";
            case "near.default-radius" -> "附近玩家默认查询半径";
            case "near.max-radius" -> "附近玩家最大查询半径";
            case "color.signs" -> "允许告示牌使用颜色代码";
            case "color.anvil" -> "允许铁砧命名使用颜色代码";
            case "kits.first-join-enabled" -> "首次加入时发放礼包";
            case "kits.gui" -> "启用礼包选择界面";
            case "worth.sell-modified-items" -> "允许出售修改过的物品";
            case "worth.allow-creative-sell" -> "允许出售创造模式物品";
            case "powertool.enabled" -> "启用物品绑定命令";
            default -> "其他设置";
        }, e.getValue()));

        m.webPanel(WebPanel.of("essentials", "基础命令").icon("wrench").group("玩家").order(20).permission(VIEW)
                .section(new WebPanel.Stats("服务器概况", "stats").refresh(10))
                .section(new WebPanel.Table("在线玩家状态", "players").rowKey("uuid")
                        .column("name", "玩家", "player")
                        .column("world", "世界")
                        .column("gamemode", "模式", "tag")
                        .column("health", "生命", "number")
                        .column("food", "饱食", "number")
                        .column("ping", "延迟", "number")
                        .column("afk", "AFK", "bool")
                        .column("vanished", "隐身", "bool")
                        .column("god", "上帝", "bool")
                        .column("fly", "飞行", "bool")
                        .rowAction(new WebPanel.Action("heal", "治疗", "players/{uuid}/heal").permission(MANAGE).style("primary"))
                        .rowAction(new WebPanel.Action("feed", "喂食", "players/{uuid}/feed").permission(MANAGE))
                        .rowAction(new WebPanel.Action("fly", "切换飞行", "players/{uuid}/fly").permission(MANAGE))
                        .rowAction(new WebPanel.Action("god", "切换上帝", "players/{uuid}/god").permission(MANAGE))
                        .rowAction(new WebPanel.Action("vanish", "切换隐身", "players/{uuid}/vanish").permission(MANAGE))
                        .rowAction(new WebPanel.Action("gamemode", "切换模式", "players/{uuid}/gamemode").permission(MANAGE).field(mode)))
                .section(new WebPanel.Table("礼包", "kits").rowKey("name")
                        .column("name", "名称")
                        .column("display", "显示名")
                        .column("items", "物品数", "number")
                        .column("cooldown", "冷却", "duration")
                        .column("once", "一次性", "bool")
                        .column("firstJoin", "首次进服", "bool")
                        .column("requirePermission", "需要权限", "bool")
                        .column("permission", "权限节点")
                        .rowAction(new WebPanel.Action("edit", "编辑", "kits/{name}/update").permission(MANAGE)
                                .field(new WebPanel.Field("display", "显示名（MiniMessage）", "text"))
                                .field(new WebPanel.Field("cooldown", "冷却（如 1d2h，0 无冷却）", "text"))
                                .field(new WebPanel.Field("once", "一次性", "bool"))
                                .field(new WebPanel.Field("firstJoin", "首次进服发放", "bool"))
                                .field(new WebPanel.Field("requirePermission", "需要权限", "bool"))
                                .field(new WebPanel.Field("icon", "图标物品 ID", "text")))
                        .rowAction(new WebPanel.Action("delete", "删除", "kits/{name}/delete").permission(MANAGE)
                                .style("danger").confirm("确定删除该礼包？")))
                .section(new WebPanel.Table("物品价格表 (worth.yml)", "worth").rowKey("item")
                        .column("item", "物品")
                        .column("price", "单价", "money")
                        .toolbarAction(new WebPanel.Action("set", "设置价格", "worth/set").permission(MANAGE).style("primary")
                                .field(new WebPanel.Field("item", "物品 ID（如 diamond）", "text").required(true))
                                .field(new WebPanel.Field("price", "单价（0 删除）", "number").required(true)))
                        .rowAction(new WebPanel.Action("edit", "修改", "worth/set").permission(MANAGE)
                                .field(new WebPanel.Field("price", "单价（0 删除）", "number").required(true)))
                        .rowAction(new WebPanel.Action("delete", "删除", "worth/{item}/delete").permission(MANAGE)
                                .style("danger").confirm("确定删除该价格？")))
                .section(form));
    }

    private String actor(WebContext ctx) {
        return "web:" + (ctx.user() == null ? "?" : ctx.user().username());
    }

    // ---------------- 统计 ----------------

    private void stats(WebContext ctx) throws Exception {
        Map<String, Object> data = sync(ctx, () -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("online", Bukkit.getOnlinePlayers().size());
            d.put("afk", m.afk().afkCount());
            d.put("vanished", m.vanish().count());
            int entities = 0, chunks = 0;
            for (var w : Bukkit.getWorlds()) {
                entities += w.getEntityCount();
                chunks += w.getLoadedChunks().length;
            }
            d.put("entities", entities);
            d.put("chunks", chunks);
            return d;
        });
        double[] tps = InfoCommands.tps();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(stat("在线玩家", data.get("online") + " / " + Bukkit.getMaxPlayers(), "users"));
        out.add(stat("AFK", data.get("afk"), "coffee"));
        out.add(stat("隐身", data.get("vanished"), "eye-off"));
        out.add(stat("TPS(1m)", tps == null ? "N/A" : String.format(Locale.ROOT, "%.2f", Math.min(20, tps[0])), "activity"));
        out.add(stat("内存", used + " / " + rt.maxMemory() / 1048576L + " MB", "memory-stick"));
        out.add(stat("实体 / 区块", data.get("entities") + " / " + data.get("chunks"), "box"));
        ctx.json(out);
    }

    private static Map<String, Object> stat(String label, Object value, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", String.valueOf(value));
        m.put("icon", icon);
        return m;
    }

    // ---------------- 在线玩家 ----------------

    private void players(WebContext ctx) throws Exception {
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = sync(ctx, () -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!q.isEmpty() && !p.getName().toLowerCase(Locale.ROOT).contains(q)) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("uuid", p.getUniqueId().toString());
                r.put("name", p.getName());
                r.put("world", p.getWorld().getName());
                r.put("gamemode", p.getGameMode().name());
                r.put("health", Math.round(p.getHealth() * 10) / 10.0);
                r.put("food", p.getFoodLevel());
                r.put("ping", p.getPing());
                r.put("afk", m.afk().isAfk(p.getUniqueId()));
                r.put("vanished", m.vanish().isVanished(p.getUniqueId()));
                r.put("god", m.data(p).god);
                r.put("fly", p.getAllowFlight());
                list.add(r);
            }
            return list;
        });
        sortRows(ctx, rows);
        page(ctx, rows);
    }

    private void playerAction(WebContext ctx) throws Exception {
        UUID uuid;
        try {
            uuid = UUID.fromString(ctx.pathParam("uuid"));
        } catch (IllegalArgumentException e) {
            throw HttpException.badRequest("无效 UUID");
        }
        String action = ctx.pathParam("action");
        Map<String, Object> body = ctx.bodyMap();
        String result = sync(ctx, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) throw HttpException.notFound("玩家不在线");
            switch (action) {
                case "heal" -> {
                    PlayerCommands.heal(p);
                    m.msg(p, "heal.notify");
                }
                case "feed" -> {
                    PlayerCommands.feed(p);
                    m.msg(p, "feed.notify");
                }
                case "fly" -> {
                    boolean v = !p.getAllowFlight();
                    PlayerCommands.setFly(m, p, v);
                    m.msg(p, "fly.notify", "state", m.onOff(v));
                }
                case "god" -> {
                    boolean v = !m.data(p).god;
                    PlayerCommands.setGod(m, p, v);
                    m.msg(p, "god.notify", "state", m.onOff(v));
                }
                case "vanish" -> {
                    boolean v = !m.vanish().isVanished(uuid);
                    m.vanish().setVanished(p, v, true);
                    m.msg(p, "vanish.notify", "state", m.onOff(v));
                }
                case "gamemode" -> {
                    Object mode = body == null ? null : body.get("mode");
                    GameMode gm = mode == null ? null : PlayerCommands.parseMode(String.valueOf(mode));
                    if (gm == null) {
                        try {
                            gm = GameMode.valueOf(String.valueOf(mode).toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException e) {
                            throw HttpException.badRequest("无效模式");
                        }
                    }
                    p.setGameMode(gm);
                    m.msg(p, "gamemode.notify", "mode", PlayerCommands.modeName(m, gm));
                }
                default -> throw HttpException.notFound("未知操作");
            }
            return p.getName();
        });
        m.audit(actor(ctx), "web-" + action, result);
        ctx.ok("已对 " + result + " 执行 " + action);
    }

    // ---------------- 礼包 ----------------

    private void kits(WebContext ctx) throws Exception {
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = sync(ctx, () -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (KitManager.Kit k : m.kits().all()) {
                if (!q.isEmpty() && !k.name.contains(q)) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", k.name);
                r.put("display", k.display);
                r.put("items", k.items.size());
                r.put("cooldown", k.cooldown);
                r.put("once", k.once);
                r.put("firstJoin", k.firstJoin);
                r.put("requirePermission", k.requirePermission);
                r.put("permission", k.permission());
                r.put("icon", k.icon.name().toLowerCase(Locale.ROOT));
                list.add(r);
            }
            return list;
        });
        page(ctx, rows);
    }

    private void kitUpdate(WebContext ctx) throws Exception {
        String name = ctx.pathParam("name");
        Map<String, Object> body = ctx.bodyMap();
        sync(ctx, () -> {
            KitManager.Kit k = m.kits().get(name);
            if (k == null) throw HttpException.notFound("礼包不存在");
            if (body.get("display") instanceof String d && !d.isBlank()) k.display = d;
            Object cd = body.get("cooldown");
            if (cd != null && !String.valueOf(cd).isBlank()) {
                String v = String.valueOf(cd).trim();
                if (v.equals("0")) k.cooldown = 0;
                else {
                    try {
                        Long ms = Durations.parse(v);
                        k.cooldown = ms == null ? 0 : ms;
                    } catch (IllegalArgumentException e) {
                        throw HttpException.badRequest("冷却格式错误: " + v);
                    }
                }
            }
            if (body.get("once") instanceof Boolean b) k.once = b;
            if (body.get("firstJoin") instanceof Boolean b) k.firstJoin = b;
            if (body.get("requirePermission") instanceof Boolean b) k.requirePermission = b;
            if (body.get("icon") instanceof String icon && !icon.isBlank()) {
                Material mat = Material.matchMaterial(icon);
                if (mat == null) throw HttpException.badRequest("未知物品: " + icon);
                k.icon = KitManager.iconOr(mat);
            }
            m.kits().put(k);
            return null;
        });
        m.audit(actor(ctx), "kit-update", name);
        ctx.ok("已保存礼包 " + name);
    }

    private void kitDelete(WebContext ctx) throws Exception {
        String name = ctx.pathParam("name");
        boolean ok = sync(ctx, () -> m.kits().remove(name));
        if (!ok) throw HttpException.notFound("礼包不存在");
        m.audit(actor(ctx), "kit-delete", name);
        ctx.ok("已删除礼包 " + name);
    }

    // ---------------- 价格表 ----------------

    private void worth(WebContext ctx) throws Exception {
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = sync(ctx, () -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (var e : m.worth().all().entrySet()) {
                String item = e.getKey().name().toLowerCase(Locale.ROOT);
                if (!q.isEmpty() && !item.contains(q)) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("item", item);
                r.put("price", e.getValue());
                list.add(r);
            }
            return list;
        });
        sortRows(ctx, rows);
        page(ctx, rows);
    }

    @SuppressWarnings("unchecked")
    private void worthSet(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        Object item = body.get("item");
        if ((item == null || String.valueOf(item).isBlank()) && body.get("row") instanceof Map<?, ?> row) {
            item = ((Map<String, Object>) row).get("item");
        }
        if (item == null) throw HttpException.badRequest("缺少物品");
        double price;
        try {
            price = Double.parseDouble(String.valueOf(body.get("price")));
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("价格无效");
        }
        String itemName = String.valueOf(item);
        String result = sync(ctx, () -> {
            Material mat = ItemCommands.parseMaterial(m, itemName);
            if (mat == null || !mat.isItem()) throw HttpException.badRequest("未知物品: " + itemName);
            m.worth().set(mat, price);
            return mat.name();
        });
        m.audit(actor(ctx), "setworth", result + "=" + price);
        ctx.ok("已设置 " + result + " 价格为 " + price);
    }

    private void worthDelete(WebContext ctx) throws Exception {
        String item = ctx.pathParam("item");
        sync(ctx, () -> {
            Material mat = Material.matchMaterial(item);
            if (mat == null) throw HttpException.badRequest("未知物品");
            m.worth().set(mat, 0);
            return null;
        });
        m.audit(actor(ctx), "delworth", item);
        ctx.ok("已删除 " + item);
    }

    // ---------------- 配置 ----------------

    private void configLoad(WebContext ctx) throws Exception {
        Map<String, Object> out = sync(ctx, () -> {
            Map<String, Object> d = new LinkedHashMap<>();
            for (var e : CONFIG_FIELDS.entrySet()) d.put(e.getKey(), m.config().get(e.getKey()));
            return d;
        });
        ctx.json(out);
    }

    private void configSave(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        sync(ctx, () -> {
            for (var e : CONFIG_FIELDS.entrySet()) {
                if (!body.containsKey(e.getKey())) continue;
                Object v = body.get(e.getKey());
                if (e.getValue().equals("number")) {
                    try {
                        v = (int) Double.parseDouble(String.valueOf(v));
                    } catch (NumberFormatException ex) {
                        throw HttpException.badRequest(e.getKey() + " 不是数字");
                    }
                } else if (e.getValue().equals("bool")) {
                    v = v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
                }
                m.config().set(e.getKey(), v);
            }
            m.saveConfig();
            m.onReload();
            return null;
        });
        m.audit(actor(ctx), "config-save", "web");
        ctx.ok("配置已保存（命令开关类配置需重载模块生效）");
    }

    // ---------------- 工具 ----------------

    /** ctx.sync 包装：解包被 ExecutionException 等包裹的 HttpException，保证返回正确状态码。 */
    private static <T> T sync(WebContext ctx, java.util.concurrent.Callable<T> task) throws Exception {
        try {
            return ctx.sync(task);
        } catch (HttpException e) {
            throw e;
        } catch (Exception e) {
            Throwable t = e;
            while (t != null) {
                if (t instanceof HttpException he) throw he;
                t = t.getCause();
            }
            throw e;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void sortRows(WebContext ctx, List<Map<String, Object>> rows) {
        String sort = ctx.query("sort");
        if (sort == null || sort.isEmpty()) return;
        Comparator<Map<String, Object>> c = (a, b) -> {
            Object x = a.get(sort), y = b.get(sort);
            if (x == null || y == null) return x == null ? (y == null ? 0 : -1) : 1;
            if (x instanceof Comparable cx && x.getClass() == y.getClass()) return cx.compareTo(y);
            return String.valueOf(x).compareTo(String.valueOf(y));
        };
        if ("desc".equalsIgnoreCase(ctx.query("order"))) c = c.reversed();
        rows.sort(c);
    }

    private static void page(WebContext ctx, List<Map<String, Object>> rows) {
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.max(1, Math.min(500, ctx.queryInt("size", 20)));
        int from = Math.min(rows.size(), (page - 1) * size);
        int to = Math.min(rows.size(), from + size);
        ctx.page(rows.size(), rows.subList(from, to));
    }
}
