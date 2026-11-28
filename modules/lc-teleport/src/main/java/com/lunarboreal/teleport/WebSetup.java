package com.lunarboreal.teleport;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Web 面板：地标管理、玩家家列表、配置表单、统计。 */
final class WebSetup {

    private static final String VIEW = "teleport.view";
    private static final String MANAGE = "teleport.manage";
    private static final String[] KEYS = {"tpa", "tpahere", "home", "warp", "spawn", "back", "rtp"};

    /** 表单字段 key → 配置路径与类型（int / double / bool）。 */
    private record Opt(String key, String path, String type, String label, String help) {}

    private final TeleportModule m;
    private final List<Opt> opts = new ArrayList<>();

    WebSetup(TeleportModule m) {
        this.m = m;
        opts.add(new Opt("warmup", "teleport.warmup", "int", "默认预热(秒)", "传送前站立不动的时间，0 立即传送"));
        opts.add(new Opt("cancel_on_move", "teleport.cancel-on-move", "bool", "移动取消预热", null));
        opts.add(new Opt("cancel_on_damage", "teleport.cancel-on-damage", "bool", "受伤取消预热", null));
        opts.add(new Opt("safe_location", "teleport.safe-location", "bool", "安全落点检查", "避免岩浆/虚空/窒息"));
        opts.add(new Opt("check_land", "teleport.check-land", "bool", "领地 TELEPORT 权限检查", null));
        opts.add(new Opt("use_async", "teleport.use-async", "bool", "Paper 异步传送", null));
        opts.add(new Opt("tpa_timeout", "tpa.timeout", "int", "tpa 请求超时(秒)", null));
        opts.add(new Opt("home_default_limit", "homes.default-limit", "int", "默认家数量上限", null));
        opts.add(new Opt("bed_home", "homes.bed-home", "bool", "床作为 bed 家", null));
        opts.add(new Opt("spawn_on_first_join", "spawn.on-first-join", "bool", "首次加入到出生点", null));
        opts.add(new Opt("spawn_on_daily_first_join", "spawn.on-daily-first-join", "bool", "每日首次上线回主城", null));
        opts.add(new Opt("spawn_daily_join_timezone", "spawn.daily-join-timezone", "text", "每日回主城时区", "留空使用服务器时区，例如 Asia/Shanghai"));
        opts.add(new Opt("spawn_on_respawn", "spawn.on-respawn", "bool", "启用死亡回主城", null));
        opts.add(new Opt("spawn_respawn_default_enabled", "spawn.respawn-default-enabled", "bool", "玩家默认开启死亡回主城", "玩家可用 /spawn ondeath on 或 off 调整"));
        opts.add(new Opt("back_on_death", "back.on-death", "bool", "记录死亡点", null));
        opts.add(new Opt("back_persist", "back.persist", "bool", "/back 位置持久化", "重启后仍可 /back"));
        opts.add(new Opt("back_safe_search_radius", "back.safe-search-radius", "int", "/back 安全搜索半径", "至少 20 格；当前 Y 层无安全位置后逐层向上搜索"));
        opts.add(new Opt("warp_gui", "warps.gui.enabled", "bool", "地标 GUI", "关闭则 /warps 显示文本列表"));
        opts.add(new Opt("warp_gui_icon", "warps.gui.default-icon", "text", "地标默认图标", "物品 ID，如 ENDER_PEARL"));
        for (String k : KEYS) {
            opts.add(new Opt("warmup_" + k, "warmups." + k, "int", k + " 预热(秒)", null));
            opts.add(new Opt("cooldown_" + k, "cooldowns." + k, "int", k + " 冷却(秒)", null));
            opts.add(new Opt("cost_" + k, "costs." + k, "double", k + " 费用", null));
        }
        opts.add(new Opt("cost_sethome", "costs.sethome", "double", "sethome 费用", null));
        opts.add(new Opt("rtp_max_attempts", "rtp.max-attempts", "int", "rtp 最大尝试次数", null));
        opts.add(new Opt("rtp_default_radius", "rtp.default-radius", "int", "rtp 默认半径", "默认以玩家为中心的 1000 格圆形范围，未单独配置的世界使用此值"));
        opts.add(new Opt("rtp_avoid_lands", "rtp.avoid-lands", "bool", "rtp 避开领地", null));
        opts.add(new Opt("rtp_avoid_biomes", "rtp.avoid-biomes", "list", "rtp 避开的群系", "群系关键字以逗号或换行分隔，例如 ocean, river"));
    }

    void register() {
        m.webPermission(VIEW, "查看传送数据");
        m.webPermission(MANAGE, "管理传送（地标/家/配置）");

        m.webRoutes(r -> r
                .get("/stats", VIEW, this::stats)
                .get("/warps", VIEW, this::warps)
                .post("/warps/{name}/delete", MANAGE, this::deleteWarp)
                .post("/warps/{name}/edit", MANAGE, this::editWarp)
                .get("/homes", VIEW, this::homes)
                .post("/homes/{uuid}/{name}/delete", MANAGE, this::deleteHome)
                .get("/config", VIEW, this::loadConfig)
                .post("/config", MANAGE, this::saveConfig)
                .get("/rtp", VIEW, this::rtpWorlds)
                .post("/rtp/{world}/edit", MANAGE, this::editRtp));

        WebPanel.Form form = new WebPanel.Form("传送配置", "config", "config").savePermission(MANAGE);
        for (Opt o : opts) {
            String type = switch (o.type()) {
                case "bool" -> "bool";
                case "text", "list" -> "text";
                default -> "number";
            };
            WebPanel.Field f = new WebPanel.Field(o.key(), o.label(), type);
            if (o.help() != null) f.help(o.help());
            form.field(f);
        }

        m.webPanel(WebPanel.of("teleport", "传送系统").icon("map-pin").group("玩家").order(40).permission(VIEW)
                .section(new WebPanel.Stats("概览", "stats").refresh(30))
                .section(new WebPanel.Table("地标 (warp)", "warps").rowKey("name")
                        .column(new WebPanel.Column("name", "名称", "text", true))
                        .column("location", "位置")
                        .column("restricted", "需要权限", "bool")
                        .column("permission", "权限节点", "tag")
                        .column("cost", "费用", "money")
                        .column("icon", "图标", "tag")
                        .column("description", "描述")
                        .column("creator", "创建者", "player")
                        .column(new WebPanel.Column("created", "创建时间", "datetime", true))
                        .rowAction(new WebPanel.Action("edit", "编辑", "warps/{name}/edit").style("primary").permission(MANAGE)
                                .field(new WebPanel.Field("restricted", "需要 lc.teleport.warp.<名> 权限", "bool"))
                                .field(new WebPanel.Field("cost", "费用（0 = 使用默认 costs.warp）", "number"))
                                .field(new WebPanel.Field("icon", "GUI 图标（物品 ID，如 GRASS_BLOCK，留空用默认）", "text"))
                                .field(new WebPanel.Field("description", "GUI 描述（MiniMessage，| 换行）", "text")))
                        .rowAction(new WebPanel.Action("delete", "删除", "warps/{name}/delete").style("danger")
                                .confirm("确定删除该地标？").permission(MANAGE)))
                .section(new WebPanel.Table("玩家的家（搜索玩家名或家名）", "homes").rowKey("key")
                        .column("player", "玩家", "player")
                        .column("name", "家名")
                        .column("location", "位置")
                        .column(new WebPanel.Column("created", "设置时间", "datetime", true))
                        .rowAction(new WebPanel.Action("delete", "删除", "homes/{uuid}/{name}/delete").style("danger")
                                .confirm("确定删除该家？").permission(MANAGE)))
                .section(new WebPanel.Table("随机传送世界 (rtp)", "rtp").rowKey("world").searchable(false)
                        .column("world", "世界")
                        .column("enabled", "启用", "bool")
                        .column("center_on_player", "以玩家为中心", "bool")
                        .column("center", "中心")
                        .column("min", "最小半径", "number")
                        .column("max", "最大半径", "number")
                        .rowAction(new WebPanel.Action("edit", "编辑", "rtp/{world}/edit").style("primary").permission(MANAGE)
                                .field(new WebPanel.Field("enabled", "启用该世界的 RTP", "bool"))
                                .field(new WebPanel.Field("center_on_player", "以玩家为中心", "bool"))
                                .field(new WebPanel.Field("center_x", "固定中心 X（关闭玩家中心时使用）", "number"))
                                .field(new WebPanel.Field("center_z", "固定中心 Z（关闭玩家中心时使用）", "number"))
                                .field(new WebPanel.Field("min_radius", "最小半径", "number"))
                                .field(new WebPanel.Field("max_radius", "最大半径", "number"))))
                .section(form));
    }

    // ---------------------------------------------------------------------

    private void stats(WebContext ctx) throws Exception {
        long homes = m.db().queryOne("SELECT COUNT(*) FROM " + m.homes().table(), rs -> rs.getLong(1)).orElse(0L);
        int pending = ctx.sync(() -> m.tpa().pendingCount());
        List<Map<String, Object>> out = new ArrayList<>();
        StoredLoc hub = m.spawns().stored();
        var hubStat = stat("主城", hub == null ? "未设置" : hub.display(), "landmark");
        if (hub == null) hubStat.put("valueKey", "未设置");
        out.add(hubStat);
        out.add(stat("地标", m.warps().count(), "map-pin"));
        out.add(stat("家总数", homes, "home"));
        out.add(stat("待处理 tpa", pending, "send"));
        out.add(stat("rtp 世界", m.rtp().worlds().size(), "shuffle"));
        ctx.json(out);
    }

    private static Map<String, Object> stat(String label, Object value, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("labelKey", label);
        m.put("value", String.valueOf(value));
        m.put("icon", icon);
        return m;
    }

    private void warps(WebContext ctx) {
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        List<WarpManager.Warp> list = new ArrayList<>(m.warps().all());
        list.removeIf(w -> !q.isEmpty() && !w.name().toLowerCase(Locale.ROOT).contains(q));
        Comparator<WarpManager.Warp> cmp = "created".equals(ctx.query("sort"))
                ? Comparator.comparingLong(WarpManager.Warp::created)
                : Comparator.comparing(w -> w.name().toLowerCase(Locale.ROOT));
        if ("desc".equalsIgnoreCase(ctx.query("order"))) cmp = cmp.reversed();
        list.sort(cmp);
        int page = Math.max(1, ctx.queryInt("page", 1)), size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        List<Map<String, Object>> items = new ArrayList<>();
        boolean global = m.config().getBoolean("warps.per-warp-permission", false);
        for (int i = (page - 1) * size; i < Math.min(list.size(), page * size); i++) {
            WarpManager.Warp w = list.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", w.name());
            row.put("location", w.loc().display());
            row.put("restricted", w.restricted());
            row.put("permission", w.restricted() || global ? w.permission() : "");
            row.put("cost", w.cost());
            row.put("icon", w.icon() == null ? "" : w.icon());
            row.put("description", w.description() == null ? "" : w.description());
            row.put("creator", w.creator() == null ? "" : w.creator());
            row.put("created", w.created());
            items.add(row);
        }
        ctx.page(list.size(), items);
    }

    private void deleteWarp(WebContext ctx) {
        String name = ctx.pathParam("name");
        WarpManager.Warp w = m.warps().get(name);
        if (w == null) throw HttpException.notFound("地标不存在");
        m.warps().delete(w.name()).join();
        m.audit("web:" + ctx.user().username(), "delwarp", w.name());
        ctx.ok("已删除地标 " + w.name());
    }

    private void editWarp(WebContext ctx) {
        String name = ctx.pathParam("name");
        WarpManager.Warp w = m.warps().get(name);
        if (w == null) throw HttpException.notFound("地标不存在");
        Map<String, Object> body = ctx.bodyMap();
        boolean restricted = body.containsKey("restricted") ? bool(body.get("restricted")) : w.restricted();
        double cost = body.containsKey("cost") ? num(body.get("cost"), w.cost()) : w.cost();
        if (cost < 0) throw HttpException.badRequest("费用不能为负");
        WarpManager.Warp nw = w.withAccess(restricted, cost);
        if (body.containsKey("icon")) {
            String icon = body.get("icon") == null ? "" : String.valueOf(body.get("icon")).trim();
            if (!icon.isEmpty() && org.bukkit.Material.matchMaterial(icon) == null) throw HttpException.badRequest("未知物品: " + icon);
            nw = nw.withIcon(icon.isEmpty() ? null : org.bukkit.Material.matchMaterial(icon).name());
        }
        if (body.containsKey("description")) {
            String d = body.get("description") == null ? "" : String.valueOf(body.get("description"));
            if (d.length() > 255) throw HttpException.badRequest("描述最长 255 字符");
            nw = nw.withDescription(d.isEmpty() ? null : d);
        }
        m.warps().save(nw).join();
        m.audit("web:" + ctx.user().username(), "editwarp", w.name() + " restricted=" + restricted + " cost=" + cost);
        ctx.ok("已更新地标 " + w.name());
    }

    private void homes(WebContext ctx) throws Exception {
        String q = ctx.query("q", "").trim();
        String table = m.homes().table();
        String where = "";
        List<Object> params = new ArrayList<>();
        if (!q.isEmpty()) {
            Optional<UUID> uuid = m.api().players().uuidOf(q);
            if (uuid.isPresent()) {
                where = " WHERE uuid = ?";
                params.add(uuid.get().toString());
            } else {
                where = " WHERE LOWER(name) LIKE ? OR uuid = ?";
                params.add("%" + q.toLowerCase(Locale.ROOT) + "%");
                params.add(q);
            }
        }
        long total = m.db().queryOne("SELECT COUNT(*) FROM " + table + where, rs -> rs.getLong(1), params.toArray()).orElse(0L);
        int page = Math.max(1, ctx.queryInt("page", 1)), size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        String order = "desc".equalsIgnoreCase(ctx.query("order")) ? "DESC" : "ASC";
        String sort = "created".equals(ctx.query("sort")) ? "created_at " + order : "uuid, name";
        List<Object> p2 = new ArrayList<>(params);
        p2.add(size);
        p2.add((page - 1) * size);
        List<Map<String, Object>> items = m.db().query("SELECT * FROM " + table + where + " ORDER BY " + sort + " LIMIT ? OFFSET ?",
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String uuid = rs.getString("uuid");
                    row.put("key", uuid + ":" + rs.getString("name"));
                    row.put("uuid", uuid);
                    row.put("name", rs.getString("name"));
                    row.put("location", StoredLoc.read(rs).display());
                    row.put("created", rs.getLong("created_at"));
                    return row;
                }, p2.toArray());
        for (Map<String, Object> row : items) {
            try {
                row.put("player", m.api().players().nameOf(UUID.fromString((String) row.get("uuid"))));
            } catch (IllegalArgumentException e) {
                row.put("player", row.get("uuid"));
            }
        }
        ctx.page(total, items);
    }

    private void deleteHome(WebContext ctx) {
        UUID uuid;
        try {
            uuid = UUID.fromString(ctx.pathParam("uuid"));
        } catch (IllegalArgumentException e) {
            throw HttpException.badRequest("UUID 无效");
        }
        String name = ctx.pathParam("name");
        int n = m.homes().delete(uuid, name).join();
        if (n == 0) throw HttpException.notFound("家不存在");
        String player = m.api().players().nameOf(uuid);
        m.audit("web:" + ctx.user().username(), "delhome", player + ":" + name);
        ctx.ok("已删除 " + player + " 的家 " + name);
    }

    private void loadConfig(WebContext ctx) throws Exception {
        Map<String, Object> out = ctx.sync(() -> {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Opt o : opts) {
                switch (o.type()) {
                    case "bool" -> map.put(o.key(), m.config().getBoolean(o.path()));
                    case "int" -> map.put(o.key(), m.config().getInt(o.path(), o.path().startsWith("warmups.") ? m.config().getInt("teleport.warmup") : 0));
                    case "double" -> map.put(o.key(), m.config().getDouble(o.path()));
                    case "list" -> map.put(o.key(), String.join(", ", m.config().getStringList(o.path())));
                    default -> map.put(o.key(), m.config().getString(o.path(), ""));
                }
            }
            return map;
        });
        ctx.json(out);
    }

    private void saveConfig(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        Object timezone = body.get("spawn_daily_join_timezone");
        if (body.containsKey("rtp_default_radius")) integer(body.get("rtp_default_radius"), 1, RtpManager.MAX_RADIUS);
        if (body.containsKey("back_safe_search_radius")) integer(body.get("back_safe_search_radius"), 20, Integer.MAX_VALUE);
        if (body.containsKey("rtp_max_attempts")) integer(body.get("rtp_max_attempts"), 1, Integer.MAX_VALUE);
        if (timezone != null && !String.valueOf(timezone).isBlank()) {
            try {
                java.time.ZoneId.of(String.valueOf(timezone).trim());
            } catch (java.time.DateTimeException e) {
                throw HttpException.badRequest("时区无效，例如 Asia/Shanghai");
            }
        }
        ctx.sync(() -> {
            for (Opt o : opts) {
                if (!body.containsKey(o.key())) continue;
                Object v = body.get(o.key());
                switch (o.type()) {
                    case "bool" -> m.config().set(o.path(), bool(v));
                    case "int" -> m.config().set(o.path(), Math.max(0, (int) num(v, 0)));
                    case "double" -> m.config().set(o.path(), Math.max(0, num(v, 0)));
                    case "list" -> m.config().set(o.path(), java.util.Arrays.stream(
                            (v == null ? "" : String.valueOf(v)).split("[,\\r\\n]+"))
                            .map(String::trim).filter(s -> !s.isEmpty()).toList());
                    default -> m.config().set(o.path(), v == null ? "" : String.valueOf(v));
                }
            }
            m.saveConfig();
            m.onReload();
            return null;
        });
        m.audit("web:" + ctx.user().username(), "config", "更新传送配置");
        ctx.ok("已保存并重载配置");
    }

    private void rtpWorlds(WebContext ctx) throws Exception {
        List<Map<String, Object>> items = ctx.sync(() -> {
            List<Map<String, Object>> list = new ArrayList<>();
            var sec = m.config().getConfigurationSection("rtp.worlds");
            var names = new LinkedHashSet<String>();
            Bukkit.getWorlds().forEach(w -> names.add(w.getName()));
            if (sec != null) names.addAll(sec.getKeys(false));
            for (String w : names) {
                String base = "rtp.worlds." + w + ".";
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("world", w);
                boolean relative = m.config().getBoolean(base + "center-on-player", true);
                row.put("enabled", m.config().getBoolean(base + "enabled", true));
                row.put("center_on_player", relative);
                row.put("center", relative ? "玩家当前位置" : m.config().getInt(base + "center-x") + ", " + m.config().getInt(base + "center-z"));
                row.put("center_x", m.config().getInt(base + "center-x"));
                row.put("center_z", m.config().getInt(base + "center-z"));
                int min = m.config().getInt(base + "min-radius", 0);
                int max = m.config().getInt(base + "max-radius", m.rtp().defaultRadius());
                row.put("min", min);
                row.put("max", max);
                row.put("min_radius", min);
                row.put("max_radius", max);
                list.add(row);
            }
            return list;
        });
        ctx.page(items.size(), items);
    }

    private void editRtp(WebContext ctx) throws Exception {
        String world = ctx.pathParam("world");
        Map<String, Object> body = ctx.bodyMap();
        String base = "rtp.worlds." + world + ".";
        String error = ctx.sync(() -> {
            if (Bukkit.getWorld(world) == null && !m.config().isConfigurationSection("rtp.worlds." + world)) return "世界不存在";
            int min = body.containsKey("min_radius") ? integer(body.get("min_radius"), 0, RtpManager.MAX_RADIUS - 1) : m.config().getInt(base + "min-radius");
            int max = body.containsKey("max_radius") ? integer(body.get("max_radius"), 1, RtpManager.MAX_RADIUS) : m.config().getInt(base + "max-radius", m.rtp().defaultRadius());
            if (min < 0 || max <= min) return "半径无效：需要 0 <= 最小 < 最大";
            int centerX = body.containsKey("center_x") ? integer(body.get("center_x"), -RtpManager.MAX_RADIUS, RtpManager.MAX_RADIUS) : m.config().getInt(base + "center-x");
            int centerZ = body.containsKey("center_z") ? integer(body.get("center_z"), -RtpManager.MAX_RADIUS, RtpManager.MAX_RADIUS) : m.config().getInt(base + "center-z");
            m.config().set(base + "center-x", centerX);
            m.config().set(base + "center-z", centerZ);
            if (body.containsKey("enabled")) m.config().set(base + "enabled", bool(body.get("enabled")));
            if (body.containsKey("center_on_player")) m.config().set(base + "center-on-player", bool(body.get("center_on_player")));
            m.config().set(base + "min-radius", min);
            m.config().set(base + "max-radius", max);
            m.saveConfig();
            m.onReload();
            return null;
        });
        if (error != null) throw HttpException.badRequest(error);
        m.audit("web:" + ctx.user().username(), "rtp-config", world);
        ctx.ok("已更新 " + world + " 的 rtp 参数");
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        return v != null && (v.toString().equalsIgnoreCase("true") || v.toString().equals("1"));
    }

    private static double num(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return def;
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int integer(Object value, int min, int max) {
        double number = num(value, Double.NaN);
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max)
            throw HttpException.badRequest("需要 " + min + " 到 " + max + " 之间的整数");
        return (int) number;
    }
}
