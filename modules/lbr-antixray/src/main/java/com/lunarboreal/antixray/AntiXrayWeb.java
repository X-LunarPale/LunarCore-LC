package com.lunarboreal.antixray;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 反矿透 Web 面板与路由。 */
final class AntiXrayWeb {

    private enum Kind { STR, BOOL, INT, DOUBLE, LIST, MAP }

    private record Spec(String key, String label, Kind kind, String help) {}

    private static final List<Spec> CONFIG = List.of(
            new Spec("fake-ore.enabled", "启用方案二：假矿伪装", Kind.BOOL, "默认关闭；Paper 服务器建议优先使用内置 anti-xray"),
            new Spec("fake-ore.max-y", "假矿：最大高度", Kind.INT, null),
            new Spec("fake-ore.radius-chunks", "假矿：处理半径（区块，0=跟随视距）", Kind.INT, null),
            new Spec("fake-ore.chunks-per-tick", "假矿：每 2 tick 最多计算区块数", Kind.INT, null),
            new Spec("fake-ore.packets-per-tick", "假矿：每玩家每 2 tick 最多发包数", Kind.INT, null),
            new Spec("fake-ore.worlds", "假矿：启用世界（每行一个，留空=全部）", Kind.LIST, null),
            new Spec("fake-ore.ores", "假矿：伪装的矿石（每行一个）", Kind.LIST, null),
            new Spec("detection.enabled", "启用方案三：挖矿检测", Kind.BOOL, null),
            new Spec("detection.window-minutes", "检测窗口（分钟）", Kind.INT, null),
            new Spec("detection.dense.threshold", "密集告警：窗口内矿脉加权数阈值", Kind.DOUBLE, null),
            new Spec("detection.ratio.min-veins", "比率告警：最少矿脉数", Kind.INT, null),
            new Spec("detection.ratio.max-stone-per-vein", "比率告警：每条矿脉石头数低于此值", Kind.DOUBLE, null),
            new Spec("detection.direct.enabled", "启用直线命中检测", Kind.BOOL, null),
            new Spec("detection.direct.threshold", "直线命中：窗口内次数阈值", Kind.INT, null),
            new Spec("detection.direct.lookback-blocks", "直线命中：回看挖掘方块数", Kind.INT, null),
            new Spec("detection.direct.min-start-distance", "直线命中：路径起点最小距离", Kind.DOUBLE, null),
            new Spec("detection.alert-cooldown-seconds", "同类告警冷却（秒）", Kind.INT, null),
            new Spec("detection.rare-ores", "稀有矿及权重（YAML）", Kind.MAP, "格式：DIAMOND_ORE: 1.0"),
            new Spec("detection.common-blocks", "普通方块（每行一个）", Kind.LIST, null),
            new Spec("actions.enabled", "告警时自动执行命令", Kind.BOOL, null),
            new Spec("actions.min-alerts", "本次在线累计告警数达到多少才执行", Kind.INT, null),
            new Spec("actions.types", "触发的告警类型（DIRECT/DENSE/RATIO，每行一个，留空=全部）", Kind.LIST, null),
            new Spec("actions.commands", "执行的命令（每行一条，占位符 {player} {uuid} {type} {detail} {alerts}）", Kind.LIST, null)
    );

    private final AntiXrayModule m;

    AntiXrayWeb(AntiXrayModule m) {
        this.m = m;
    }

    void register() {
        m.webPermission("antixray.view", "查看反矿透数据");
        m.webPermission("antixray.manage", "管理反矿透配置");

        m.webRoutes(r -> r
                .get("/stats", "antixray.view", this::stats)
                .get("/suspects", "antixray.view", this::suspects)
                .get("/alerts", "antixray.view", this::alerts)
                .get("/paper", "antixray.view", this::paperList)
                .post("/paper/{target}/preset", "antixray.manage", this::paperPreset)
                .post("/paper/{target}/edit", "antixray.manage", this::paperEdit)
                .get("/config", "antixray.manage", this::loadConfig)
                .post("/config", "antixray.manage", this::saveConfig));

        WebPanel.Field preset = new WebPanel.Field("preset", "预设", "select").required(true);
        PaperXrayConfig.PRESETS.values().forEach(p -> preset.option(p.id(), p.label()));

        WebPanel.Form form = new WebPanel.Form("配置", "config", "config");
        for (Spec s : CONFIG) {
            String type = switch (s.kind()) {
                case BOOL -> "bool";
                case INT, DOUBLE -> "number";
                case LIST -> "textarea";
                case MAP -> "code";
                default -> "text";
            };
            WebPanel.Field f = new WebPanel.Field(s.key(), s.label(), type);
            if (s.help() != null) f.help(s.help());
            form.field(f);
        }

        m.webPanel(WebPanel.of("antixray", "反矿透").icon("pickaxe").group("安全").order(22).permission("antixray.view")
                .section(new WebPanel.Stats("概览", "stats").refresh(30))
                .section(new WebPanel.Table("可疑玩家排行", "suspects").rowKey("uuid")
                        .column(new WebPanel.Column("name", "玩家", "player", false))
                        .column(new WebPanel.Column("score", "可疑分", "number", false))
                        .column("alerts", "告警数", "number")
                        .column("perThousand", "每千石头稀有矿", "text")
                        .column("rare", "稀有矿", "number")
                        .column("stone", "普通方块", "number")
                        .column("lastAlert", "最近告警", "datetime")
                        .column("online", "在线", "bool"))
                .section(new WebPanel.Table("告警记录", "alerts")
                        .column(new WebPanel.Column("id", "ID", "number", true))
                        .column(new WebPanel.Column("created", "时间", "datetime", true))
                        .column("name", "玩家", "player")
                        .column("typeLabel", "类型", "tag")
                        .column("detail", "详情")
                        .column("location", "位置"))
                .section(new WebPanel.Table("Paper anti-xray 设置（修改后需重启服务器生效）", "paper").rowKey("target").searchable(false)
                        .column("target", "目标")
                        .column("enabled", "启用", "bool")
                        .column("engine", "引擎")
                        .column("maxHeight", "最大高度")
                        .column("updateRadius", "更新半径")
                        .column("hidden", "隐藏方块数", "number")
                        .column("inherited", "继承默认的键")
                        .column("file", "文件")
                        .rowAction(new WebPanel.Action("preset", "应用预设", "paper/{target}/preset")
                                .style("primary").permission("antixray.manage").field(preset)
                                .confirm("将覆盖该目标的 anti-xray 设置，需重启生效，继续？"))
                        .rowAction(new WebPanel.Action("edit", "修改", "paper/{target}/edit").permission("antixray.manage")
                                .field(new WebPanel.Field("enabled", "启用", "bool").defaultValue(true))
                                .field(new WebPanel.Field("engine-mode", "引擎模式", "select")
                                        .option("1", "1：替换为石头").option("2", "2：随机方块").option("3", "3：分层混淆"))
                                .field(new WebPanel.Field("max-block-height", "最大高度", "number").placeholder("64"))
                                .field(new WebPanel.Field("update-radius", "更新半径", "number").placeholder("2"))
                                .field(new WebPanel.Field("lava-obscures", "岩浆遮挡视为隐藏", "bool"))))
                .section(new WebPanel.Text("说明", """
                        - **方案一（推荐）**：Paper 内置 anti-xray 在发包前混淆区块数据，最安全；修改 `config/paper-world-defaults.yml` 或 `<世界>/paper-world.yml` 后需**重启**。
                        - **方案二**：自研假矿，用 sendBlockChange 把未暴露矿石伪装为石头。区块原始数据先到客户端，存在短暂窗口，无法防御收包即缓存的作弊端，仅作补充。
                        - **方案三**：检测挖矿行为（稀有矿/石头比率、密集挖矿、直线命中未暴露矿脉），告警通知拥有 `lbr.antixray.notify` 的管理员。"""))
                .section(form));
    }

    // ======================================================================

    private void stats(WebContext ctx) throws Exception {
        MiningMonitor mon = m.monitor();
        long dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long today = mon.db().queryOne("SELECT COUNT(*) AS c FROM " + mon.alertTable + " WHERE created >= ?", rs -> rs.getLong("c"), dayStart).orElse(0L);
        long total = mon.db().queryOne("SELECT COUNT(*) AS c FROM " + mon.alertTable, rs -> rs.getLong("c")).orElse(0L);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(stat("今日告警", today, "siren"));
        out.add(stat("告警总数", total, "list"));
        out.add(stat("Paper anti-xray", m.paperAvailable() ? "可用" : "不可用", "shield"));
        out.add(stat("假矿方案", m.fakeOre().isEnabled() ? "已开启" : "未开启", "eye-off"));
        out.add(stat("伪装中的矿石", m.fakeOre().hiddenCount(), "gem"));
        out.add(stat("在线玩家", Bukkit.getOnlinePlayers().size(), "users"));
        ctx.json(out);
    }

    private static Map<String, Object> stat(String label, Object value, String icon) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("label", label);
        o.put("value", String.valueOf(value));
        o.put("icon", icon);
        return o;
    }

    private void suspects(WebContext ctx) throws Exception {
        List<Map<String, Object>> all = m.monitor().suspectsSync();
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) all.removeIf(r -> !String.valueOf(r.get("name")).toLowerCase(Locale.ROOT).contains(q));
        page(ctx, all);
    }

    private static void page(WebContext ctx, List<Map<String, Object>> all) {
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.min(200, Math.max(1, ctx.queryInt("size", 20)));
        int from = Math.min(all.size(), (page - 1) * size);
        ctx.page(all.size(), all.subList(from, Math.min(all.size(), from + size)));
    }

    private void alerts(WebContext ctx) throws Exception {
        MiningMonitor mon = m.monitor();
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.min(200, Math.max(1, ctx.queryInt("size", 20)));
        String q = ctx.query("q", "").trim();
        String sort = "created".equals(ctx.query("sort")) ? "created" : "id";
        String order = "asc".equalsIgnoreCase(ctx.query("order", "desc")) ? "ASC" : "DESC";
        String where = "";
        List<Object> params = new ArrayList<>();
        if (!q.isEmpty()) {
            where = " WHERE name LIKE ? OR type = ? OR detail LIKE ?";
            params.add("%" + q + "%");
            params.add(q.toUpperCase(Locale.ROOT));
            params.add("%" + q + "%");
        }
        long total = mon.db().queryOne("SELECT COUNT(*) AS c FROM " + mon.alertTable + where, rs -> rs.getLong("c"), params.toArray()).orElse(0L);
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> items = mon.db().query("SELECT * FROM " + mon.alertTable + where + " ORDER BY " + sort + " " + order + " LIMIT ? OFFSET ?", rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("created", rs.getLong("created"));
            row.put("uuid", rs.getString("uuid"));
            row.put("name", rs.getString("name"));
            String type = rs.getString("type");
            row.put("type", type);
            row.put("typeLabel", m.raw("types." + (type == null ? "unknown" : type.toLowerCase(Locale.ROOT))));
            row.put("detail", rs.getString("detail"));
            row.put("location", rs.getString("world") + " " + rs.getInt("x") + "," + rs.getInt("y") + "," + rs.getInt("z"));
            return row;
        }, params.toArray());
        ctx.page(total, items);
    }

    private void paperList(WebContext ctx) throws Exception {
        if (!m.paperAvailable()) { ctx.page(0, List.of()); return; }
        List<String> targets = ctx.sync(PaperXrayConfig::targets);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String t : targets) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("target", t);
            try {
                Map<String, Object> s = PaperXrayConfig.read(t);
                row.put("enabled", Boolean.parseBoolean(String.valueOf(s.get("enabled"))));
                row.put("engine", String.valueOf(s.get("engine-mode")));
                row.put("maxHeight", String.valueOf(s.get("max-block-height")));
                row.put("updateRadius", String.valueOf(s.get("update-radius")));
                row.put("hidden", ((List<?>) s.get("hidden-blocks")).size());
                row.put("inherited", String.join(", ", castList(s.get("inherited"))));
                row.put("file", s.get("file"));
            } catch (Exception e) {
                row.put("file", "读取失败：" + e.getMessage());
            }
            rows.add(row);
        }
        ctx.page(rows.size(), rows);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }

    private String checkTarget(WebContext ctx) throws Exception {
        if (!m.paperAvailable()) throw HttpException.badRequest("当前服务端不是 Paper，内置 anti-xray 不可用");
        String target = ctx.pathParam("target");
        boolean ok = PaperXrayConfig.DEFAULTS.equalsIgnoreCase(target) || Boolean.TRUE.equals(ctx.sync(() -> Bukkit.getWorld(target) != null));
        if (!ok) throw HttpException.notFound("世界不存在：" + target);
        return target;
    }

    private void paperPreset(WebContext ctx) throws Exception {
        String target = checkTarget(ctx);
        String preset = String.valueOf(ctx.bodyMap().get("preset"));
        if (!PaperXrayConfig.PRESETS.containsKey(preset)) throw HttpException.badRequest("未知预设：" + preset);
        PaperXrayConfig.applyPreset(target, preset);
        m.audit("web:" + ctx.user().username(), "paper-antixray", target + ": preset " + preset);
        ctx.ok("已对 " + target + " 应用预设 " + preset + "，重启服务器后生效");
    }

    private void paperEdit(WebContext ctx) throws Exception {
        String target = checkTarget(ctx);
        Map<String, Object> body = ctx.bodyMap();
        Map<String, Object> vals = new LinkedHashMap<>();
        if (body.containsKey("enabled")) vals.put("enabled", truthy(body.get("enabled")));
        if (body.containsKey("lava-obscures")) vals.put("lava-obscures", truthy(body.get("lava-obscures")));
        for (String k : List.of("engine-mode", "max-block-height", "update-radius")) {
            Object v = body.get(k);
            if (v == null || String.valueOf(v).isBlank()) continue;
            try { vals.put(k, (int) Double.parseDouble(String.valueOf(v))); } catch (NumberFormatException e) { throw HttpException.badRequest(k + " 必须是数字"); }
        }
        if (vals.containsKey("engine-mode")) {
            int em = (Integer) vals.get("engine-mode");
            if (em < 1 || em > 3) throw HttpException.badRequest("engine-mode 只能是 1/2/3");
        }
        if (vals.isEmpty()) throw HttpException.badRequest("没有要修改的字段");
        PaperXrayConfig.write(target, vals);
        m.audit("web:" + ctx.user().username(), "paper-antixray", target + ": " + vals);
        ctx.ok("已保存 " + target + " 的 anti-xray 设置，重启服务器后生效");
    }

    private static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private void loadConfig(WebContext ctx) throws Exception {
        Map<String, Object> out = ctx.sync(() -> {
            var c = m.config();
            Map<String, Object> o = new LinkedHashMap<>();
            for (Spec s : CONFIG) {
                switch (s.kind()) {
                    case BOOL -> o.put(s.key(), c.getBoolean(s.key()));
                    case INT -> o.put(s.key(), c.getInt(s.key()));
                    case DOUBLE -> o.put(s.key(), c.getDouble(s.key()));
                    case LIST -> o.put(s.key(), String.join("\n", c.getStringList(s.key())));
                    case MAP -> {
                        YamlConfiguration y = new YamlConfiguration();
                        ConfigurationSection sec = c.getConfigurationSection(s.key());
                        if (sec != null) for (String k : sec.getKeys(false)) y.set(k, sec.get(k));
                        o.put(s.key(), y.saveToString());
                    }
                    default -> o.put(s.key(), c.getString(s.key(), ""));
                }
            }
            return o;
        });
        ctx.json(out);
    }

    private void saveConfig(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Spec s : CONFIG) {
            if (!body.containsKey(s.key())) continue;
            Object v = body.get(s.key());
            String str = v == null ? "" : String.valueOf(v).trim();
            try {
                switch (s.kind()) {
                    case BOOL -> parsed.put(s.key(), truthy(v));
                    case INT -> parsed.put(s.key(), (int) Double.parseDouble(str));
                    case DOUBLE -> parsed.put(s.key(), Double.parseDouble(str));
                    case LIST -> {
                        List<String> l = new ArrayList<>();
                        for (String line : str.split("\\r?\\n")) if (!line.isBlank()) l.add(line.trim());
                        parsed.put(s.key(), l);
                    }
                    case MAP -> {
                        YamlConfiguration y = new YamlConfiguration();
                        y.loadFromString(str);
                        Map<String, Object> mp = new LinkedHashMap<>();
                        for (String k : y.getKeys(false)) mp.put(k, y.get(k));
                        parsed.put(s.key(), mp);
                    }
                    default -> parsed.put(s.key(), str);
                }
            } catch (NumberFormatException e) {
                throw HttpException.badRequest(s.label() + " 必须是数字");
            } catch (InvalidConfigurationException e) {
                throw HttpException.badRequest(s.label() + " YAML 格式错误：" + e.getMessage());
            }
        }
        String data = ctx.sync(() -> {
            var c = m.config();
            parsed.forEach((k, v) -> {
                if (v instanceof Map<?, ?> mp) { c.set(k, null); mp.forEach((kk, vv) -> c.set(k + "." + kk, vv)); }
                else c.set(k, v);
            });
            m.monitor().reload();
            m.fakeOre().reload();
            return c.saveToString();
        });
        Files.writeString(new File(m.dataFolder(), "config.yml").toPath(), data, StandardCharsets.UTF_8);
        m.audit("web:" + ctx.user().username(), "config", "更新反矿透配置");
        ctx.ok("配置已保存并生效");
    }
}
