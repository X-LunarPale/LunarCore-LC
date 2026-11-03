package com.lunarboreal.land;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Web 面板：统计、领地列表、成员、配置。 */
final class LandWeb {

    private final LandModule m;

    LandWeb(LandModule module) {
        this.m = module;
    }

    void register() {
        m.webPermission("land.view", "查看领地");
        m.webPermission("land.manage", "管理领地（删除/转让/修改配置）");

        m.webRoutes(r -> r
                .get("/stats", "land.view", this::stats)
                .get("/lands", "land.view", this::lands)
                .post("/lands/{id}/delete", "land.manage", this::delete)
                .post("/lands/{id}/transfer", "land.manage", this::transfer)
                .get("/members", "land.view", this::members)
                .post("/members/{key}/remove", "land.manage", this::removeMember)
                .get("/config", "land.view", this::loadConfig)
                .post("/config", "land.manage", this::saveConfig));

        m.webPanel(WebPanel.of("land", "领地系统").icon("map").group("经济").order(30).permission("land.view")
                .section(new WebPanel.Stats("概览", "stats").refresh(30))
                .section(new WebPanel.Table("领地列表", "lands")
                        .column(new WebPanel.Column("id", "ID", "number", true))
                        .column(new WebPanel.Column("name", "名称", "text", true))
                        .column("world", "世界", "tag")
                        .column("coords", "坐标")
                        .column(new WebPanel.Column("area", "面积", "number", true))
                        .column("owner", "主人", "player")
                        .column(new WebPanel.Column("members", "成员数", "number", true))
                        .column("salePrice", "出售价", "text")
                        .column("frozen", "冻结", "bool")
                        .column(new WebPanel.Column("createdAt", "创建时间", "datetime", true))
                        .rowAction(new WebPanel.Action("delete", "删除", "lands/{id}/delete")
                                .style("danger").confirm("确定删除该领地？此操作不可恢复且不退款。").permission("land.manage"))
                        .rowAction(new WebPanel.Action("transfer", "转让", "lands/{id}/transfer")
                                .style("warning").permission("land.manage")
                                .field(new WebPanel.Field("player", "新主人（玩家名）", "player").required(true))))
                .section(new WebPanel.Table("领地成员", "members").rowKey("key")
                        .column("land", "领地")
                        .column("landId", "领地 ID", "number")
                        .column("player", "玩家", "player")
                        .column("role", "角色", "tag")
                        .rowAction(new WebPanel.Action("remove", "移除", "members/{key}/remove")
                                .style("danger").confirm("确定移除该成员？").permission("land.manage")))
                .section(configForm()));
    }

    private WebPanel.Form configForm() {
        return new WebPanel.Form("配置", "config", "config")
                .field(new WebPanel.Field("selectionTool", "选区工具（材料名）", "text"))
                .field(new WebPanel.Field("pricePerBlock", "每格价格", "number"))
                .field(new WebPanel.Field("noEconomy", "无经济服务时", "select").option("free", "免费圈地").option("deny", "禁止圈地"))
                .field(new WebPanel.Field("refundRatio", "删除退款比例（0–1）", "number").range(0, 1, 0.01))
                .field(new WebPanel.Field("saleTax", "交易税率（0–1）", "number").range(0, 1, 0.01))
                .field(new WebPanel.Field("minSide", "最小边长", "number"))
                .field(new WebPanel.Field("maxSide", "最大边长", "number"))
                .field(new WebPanel.Field("minArea", "最小面积", "number"))
                .field(new WebPanel.Field("maxLands", "默认最大领地数", "number").help("权限 lbr.land.limit.<n> 覆盖"))
                .field(new WebPanel.Field("maxTotalArea", "默认最大总面积", "number").help("权限 lbr.land.area.<n> 覆盖"))
                .field(new WebPanel.Field("spawnDistance", "出生点保护距离", "number"))
                .field(new WebPanel.Field("worldBlacklist", "禁止圈地的世界（每行一个）", "textarea"))
                .field(new WebPanel.Field("taxEnabled", "启用领地税", "bool"))
                .field(new WebPanel.Field("taxPerBlock", "每格每天税额", "number"))
                .field(new WebPanel.Field("taxDeleteAfterDays", "冻结多少天后删除（0 不删除）", "number"))
                .field(new WebPanel.Field("messageDisplay", "进出提示方式", "select")
                        .option("actionbar", "快捷栏上方提示").option("title", "屏幕中央标题").option("chat", "聊天栏"));
    }

    // ------------------------------------------------------------ handlers

    private void stats(WebContext ctx) throws Exception {
        List<Map<String, Object>> out = sync(ctx, () -> {
            long area = 0;
            int sale = 0, frozen = 0;
            for (Land l : m.manager().all()) {
                area += l.area();
                if (l.salePrice != null) sale++;
                if (l.frozen) frozen++;
            }
            List<Map<String, Object>> s = new ArrayList<>();
            s.add(stat("领地总数", m.manager().all().size(), "map"));
            s.add(stat("总面积", area, "square"));
            s.add(stat("领主数", m.manager().ownerCount(), "users"));
            s.add(stat("出售中", sale, "tag"));
            s.add(stat("冻结", frozen, "snowflake"));
            return s;
        });
        ctx.json(out);
    }

    private static Map<String, Object> stat(String label, Object value, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", String.valueOf(value));
        m.put("icon", icon);
        return m;
    }

    private void lands(WebContext ctx) throws Exception {
        int page = Math.max(1, ctx.queryInt("page", 1)), size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        String sort = ctx.query("sort", "id");
        boolean desc = "desc".equalsIgnoreCase(ctx.query("order", "asc"));
        List<Map<String, Object>> rows = sync(ctx, () -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Land l : m.manager().all()) {
                if (!q.isEmpty() && !l.name.toLowerCase(Locale.ROOT).contains(q)
                        && (l.ownerName == null || !l.ownerName.toLowerCase(Locale.ROOT).contains(q))
                        && !l.world.toLowerCase(Locale.ROOT).contains(q)) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", l.id);
                r.put("name", l.name);
                r.put("world", l.world);
                r.put("coords", l.coords());
                r.put("area", l.area());
                r.put("owner", l.ownerName == null ? l.owner.toString() : l.ownerName);
                r.put("ownerUuid", l.owner.toString());
                r.put("members", l.members.size());
                r.put("salePrice", l.salePrice == null ? "" : l.salePrice.toPlainString());
                r.put("frozen", l.frozen);
                r.put("createdAt", l.createdAt);
                list.add(r);
            }
            return list;
        });
        Comparator<Map<String, Object>> cmp = switch (sort) {
            case "name" -> Comparator.comparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT));
            case "area" -> Comparator.comparingLong(r -> (long) r.get("area"));
            case "members" -> Comparator.comparingInt(r -> (int) r.get("members"));
            case "createdAt" -> Comparator.comparingLong(r -> (long) r.get("createdAt"));
            default -> Comparator.comparingLong(r -> (long) r.get("id"));
        };
        rows.sort(desc ? cmp.reversed() : cmp);
        ctx.page(rows.size(), slice(rows, page, size));
    }

    /** 主线程执行，并把内部抛出的 HttpException 原样抛出（避免被包装成 500）。 */
    private static <T> T sync(WebContext ctx, java.util.concurrent.Callable<T> task) throws Exception {
        try {
            return ctx.sync(task);
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof HttpException h) throw h;
            throw e;
        }
    }

    private static <T> List<T> slice(List<T> list, int page, int size) {
        int from = Math.min(list.size(), (page - 1) * size);
        return list.subList(from, Math.min(list.size(), from + size));
    }

    private Land landFromPath(WebContext ctx) {
        long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            throw HttpException.badRequest("无效的领地 ID");
        }
        Land l = m.manager().byId(id);
        if (l == null) throw HttpException.notFound("领地不存在");
        return l;
    }

    private void delete(WebContext ctx) throws Exception {
        String name = sync(ctx, () -> {
            Land l = landFromPath(ctx);
            m.deleteLand(l);
            m.notifyOwner(l, "admin.deleted-notify", "land", l.name);
            m.audit("web:" + ctx.user().username(), "admin-delete", l.name + " owner=" + l.ownerName + " " + l.world + " " + l.coords());
            return l.name;
        });
        ctx.ok("已删除领地 " + name);
    }

    private void transfer(WebContext ctx) throws Exception {
        Object player = ctx.bodyMap().get("player");
        if (player == null || String.valueOf(player).isBlank()) throw HttpException.badRequest("请填写玩家名");
        String pname = String.valueOf(player).trim();
        String msg = sync(ctx, () -> {
            Land l = landFromPath(ctx);
            UUID u = m.resolvePlayer(pname);
            if (u == null) throw HttpException.badRequest("找不到玩家 " + pname);
            if (l.isOwner(u)) throw HttpException.badRequest("该玩家已是主人");
            String old = l.ownerName;
            String newName = m.nameOf(u);
            m.manager().changeOwner(l, u, newName);
            l.salePrice = null;
            m.storage().save(l);
            m.storage().saveMembers(l);
            Player t = Bukkit.getPlayer(u);
            if (t != null) m.msg(t, "transfer.received", "land", l.name, "player", "Web");
            m.audit("web:" + ctx.user().username(), "admin-transfer", l.name + " " + old + " -> " + newName);
            return "已将 " + l.name + " 转让给 " + newName;
        });
        ctx.ok(msg);
    }

    private void members(WebContext ctx) throws Exception {
        int page = Math.max(1, ctx.queryInt("page", 1)), size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = sync(ctx, () -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Land l : m.manager().all()) {
                boolean landMatch = q.isEmpty() || l.name.toLowerCase(Locale.ROOT).contains(q) || String.valueOf(l.id).equals(q);
                for (Land.Member mb : l.members.values()) {
                    if (!landMatch && (mb.name() == null || !mb.name().toLowerCase(Locale.ROOT).contains(q))) continue;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("key", l.id + ":" + mb.uuid());
                    r.put("land", l.name);
                    r.put("landId", l.id);
                    r.put("player", mb.name() == null ? mb.uuid().toString() : mb.name());
                    r.put("uuid", mb.uuid().toString());
                    r.put("role", mb.role() == Land.Role.MANAGER ? "管理员" : "成员");
                    list.add(r);
                }
            }
            return list;
        });
        ctx.page(rows.size(), slice(rows, page, size));
    }

    private void removeMember(WebContext ctx) throws Exception {
        String key = ctx.pathParam("key");
        int idx = key == null ? -1 : key.indexOf(':');
        if (idx < 0) throw HttpException.badRequest("无效参数");
        long id;
        UUID u;
        try {
            id = Long.parseLong(key.substring(0, idx));
            u = UUID.fromString(key.substring(idx + 1));
        } catch (IllegalArgumentException e) {
            throw HttpException.badRequest("无效参数");
        }
        String msg = sync(ctx, () -> {
            Land l = m.manager().byId(id);
            if (l == null) throw HttpException.notFound("领地不存在");
            Land.Member mb = l.members.remove(u);
            if (mb == null) throw HttpException.notFound("成员不存在");
            m.storage().saveMembers(l);
            m.audit("web:" + ctx.user().username(), "admin-untrust", l.name + " " + mb.name());
            return "已从 " + l.name + " 移除 " + mb.name();
        });
        ctx.ok(msg);
    }

    private void loadConfig(WebContext ctx) throws Exception {
        Map<String, Object> out = sync(ctx, () -> {
            YamlConfiguration c = m.config();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("selectionTool", c.getString("selection-tool"));
            v.put("pricePerBlock", c.getDouble("economy.price-per-block"));
            v.put("noEconomy", c.getString("economy.no-economy"));
            v.put("refundRatio", c.getDouble("economy.refund-ratio"));
            v.put("saleTax", c.getDouble("economy.sale-tax"));
            v.put("minSide", c.getInt("limits.min-side"));
            v.put("maxSide", c.getInt("limits.max-side"));
            v.put("minArea", c.getInt("limits.min-area"));
            v.put("maxLands", c.getInt("limits.max-lands"));
            v.put("maxTotalArea", c.getLong("limits.max-total-area"));
            v.put("spawnDistance", c.getInt("limits.spawn-distance"));
            v.put("worldBlacklist", String.join("\n", c.getStringList("limits.world-blacklist")));
            v.put("taxEnabled", c.getBoolean("tax.enabled"));
            v.put("taxPerBlock", c.getDouble("tax.per-block-per-day"));
            v.put("taxDeleteAfterDays", c.getInt("tax.delete-after-days"));
            v.put("messageDisplay", c.getString("messages.display"));
            return v;
        });
        ctx.json(out);
    }

    private void saveConfig(WebContext ctx) throws Exception {
        Map<String, Object> b = ctx.bodyMap();
        sync(ctx, () -> {
            YamlConfiguration c = m.config();
            setStr(c, b, "selectionTool", "selection-tool");
            setNum(c, b, "pricePerBlock", "economy.price-per-block", false);
            setStr(c, b, "noEconomy", "economy.no-economy");
            setNum(c, b, "refundRatio", "economy.refund-ratio", false);
            setNum(c, b, "saleTax", "economy.sale-tax", false);
            setNum(c, b, "minSide", "limits.min-side", true);
            setNum(c, b, "maxSide", "limits.max-side", true);
            setNum(c, b, "minArea", "limits.min-area", true);
            setNum(c, b, "maxLands", "limits.max-lands", true);
            setNum(c, b, "maxTotalArea", "limits.max-total-area", true);
            setNum(c, b, "spawnDistance", "limits.spawn-distance", true);
            if (b.get("worldBlacklist") != null) {
                List<String> worlds = new ArrayList<>();
                for (String s : String.valueOf(b.get("worldBlacklist")).split("[\\r\\n,]+")) if (!s.isBlank()) worlds.add(s.trim());
                c.set("limits.world-blacklist", worlds);
            }
            if (b.get("taxEnabled") instanceof Boolean bool) c.set("tax.enabled", bool);
            setNum(c, b, "taxPerBlock", "tax.per-block-per-day", false);
            setNum(c, b, "taxDeleteAfterDays", "tax.delete-after-days", true);
            setStr(c, b, "messageDisplay", "messages.display");
            m.saveConfig();
            m.onReload();
            m.audit("web:" + ctx.user().username(), "config", b.toString());
            return null;
        });
        ctx.ok("配置已保存");
    }

    private static void setStr(YamlConfiguration c, Map<String, Object> b, String key, String path) {
        Object v = b.get(key);
        if (v != null && !String.valueOf(v).isBlank()) c.set(path, String.valueOf(v).trim());
    }

    private static void setNum(YamlConfiguration c, Map<String, Object> b, String key, String path, boolean integer) {
        Object v = b.get(key);
        if (v == null) return;
        double d;
        try {
            d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw HttpException.badRequest(key + " 不是有效数字");
        }
        if (integer) c.set(path, (long) d);
        else c.set(path, d);
    }
}
