package com.lunarboreal.ban;

import com.lunarboreal.api.service.BanService.Punishment;
import com.lunarboreal.api.service.BanService.Type;
import com.lunarboreal.api.util.Durations;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
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
import java.util.Optional;
import java.util.Set;

/** 封禁模块 Web 面板与 REST 路由。 */
final class BanWeb {

    private static final Set<String> SORTABLE = Set.of("id", "type", "name", "operator", "created", "expires");

    private final BanModule m;

    BanWeb(BanModule m) {
        this.m = m;
    }

    void register() {
        m.webPermission("ban.view", "查看封禁记录");
        m.webPermission("ban.manage", "管理封禁（处罚/撤销/配置）");

        m.webRoutes(r -> r
                .get("/stats", "ban.view", this::stats)
                .get("/punishments/all", "ban.view", ctx -> list(ctx, false))
                .get("/punishments/active", "ban.view", ctx -> list(ctx, true))
                .post("/punishments/{id}/revoke", "ban.manage", this::revoke)
                .post("/punish", "ban.manage", this::punish)
                .get("/config", "ban.manage", this::loadConfig)
                .post("/config", "ban.manage", this::saveConfig));

        WebPanel.Action addAction = new WebPanel.Action("punish", "新增处罚", "punish")
                .style("primary").permission("ban.manage")
                .field(new WebPanel.Field("player", "玩家（名字/UUID，IP 封禁可直接填 IP）", "player").required(true))
                .field(new WebPanel.Field("type", "类型", "select").required(true).defaultValue("BAN")
                        .option("BAN", "封禁").option("IPBAN", "IP 封禁").option("MUTE", "禁言")
                        .option("WARN", "警告").option("KICK", "踢出"))
                .field(new WebPanel.Field("duration", "时长", "duration").placeholder("如 7d、12h；留空为永久").help("警告/踢出忽略此项"))
                .field(new WebPanel.Field("reason", "原因（可填模板 ID，如 hack）", "text"))
                .field(new WebPanel.Field("silent", "静默（不全服广播）", "bool").defaultValue(false));
        WebPanel.Action revokeAction = new WebPanel.Action("revoke", "撤销", "punishments/{id}/revoke")
                .style("danger").confirm("确定撤销该处罚？").permission("ban.manage");

        m.webPanel(WebPanel.of("ban", "封禁管理").icon("gavel").group("安全").order(20).permission("ban.view")
                .section(new WebPanel.Stats("概览", "stats").refresh(30))
                .section(table("生效中的处罚", "punishments/active", addAction, revokeAction))
                .section(table("全部处罚记录", "punishments/all", null, revokeAction))
                .section(new WebPanel.Text("筛选说明", "搜索框支持：输入类型名 `ban` / `ipban` / `mute` / `warn` / `kick`（或 `type:ban`）按类型筛选，"
                        + "`active` 只看生效中，其余文字匹配玩家名 / IP / 原因 / 操作者，可组合使用，如 `mute steve`。"))
                .section(configForm()));
    }

    private WebPanel.Table table(String title, String endpoint, WebPanel.Action toolbar, WebPanel.Action row) {
        WebPanel.Table t = new WebPanel.Table(title, endpoint)
                .column(new WebPanel.Column("id", "ID", "number", true))
                .column(new WebPanel.Column("typeLabel", "类型", "tag", false))
                .column(new WebPanel.Column("name", "玩家", "player", true))
                .column("ip", "IP")
                .column("reason", "原因")
                .column(new WebPanel.Column("operator", "操作者", "text", true))
                .column(new WebPanel.Column("created", "时间", "datetime", true))
                .column(new WebPanel.Column("expires", "到期", "datetime", true))
                .column("status", "状态", "tag")
                .rowAction(row);
        if (toolbar != null) t.toolbarAction(toolbar);
        return t;
    }

    private WebPanel.Form configForm() {
        return new WebPanel.Form("配置", "config", "config")
                .field(new WebPanel.Field("server-name", "服务器名", "text"))
                .field(new WebPanel.Field("appeal-url", "申诉链接", "text"))
                .field(new WebPanel.Field("date-format", "日期格式", "text"))
                .field(new WebPanel.Field("default-reason", "默认原因", "text"))
                .field(new WebPanel.Field("broadcast.enabled", "全服广播处罚", "bool"))
                .field(new WebPanel.Field("broadcast.api", "广播其它模块发起的处罚", "bool"))
                .field(new WebPanel.Field("exempt-enabled", "启用 lbr.ban.exempt 免疫", "bool"))
                .field(new WebPanel.Field("warn.expire", "警告有效期", "duration").help("留空表示永不过期"))
                .field(new WebPanel.Field("warn.actions", "警告阶梯", "textarea").help("每行一条：次数=动作，例如 3=tempmute 1h 警告过多"))
                .field(new WebPanel.Field("warn.reset-after-action", "执行阶梯处罚后清空警告", "bool"))
                .field(new WebPanel.Field("muted-commands", "禁言时屏蔽的命令", "textarea").help("每行一个，不带 /"))
                .field(new WebPanel.Field("duration-limits.enabled", "启用时长上限权限", "bool"))
                .field(new WebPanel.Field("duration-limits.default", "无档位权限时的默认上限", "duration"))
                .field(new WebPanel.Field("reasons", "原因模板（YAML）", "code").help("格式：ID: {reason: 原因, duration: 30d}"));
    }

    // ======================================================================

    private void stats(WebContext ctx) throws Exception {
        PunishmentStore s = store();
        long total = s.db().queryOne("SELECT COUNT(*) AS c FROM " + s.table(), rs -> rs.getLong("c")).orElse(0L);
        long startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long today = s.db().queryOne("SELECT COUNT(*) AS c FROM " + s.table() + " WHERE created >= ?", rs -> rs.getLong("c"), startOfDay).orElse(0L);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(stat("生效封禁", s.activeBans().size(), "ban"));
        out.add(stat("生效 IP 封禁", s.activeIpBans().size(), "globe"));
        out.add(stat("生效禁言", s.activeMutes().size(), "mic-off"));
        out.add(stat("有效警告", s.activeWarnTotal(), "triangle-alert"));
        out.add(stat("今日处罚", today, "calendar"));
        out.add(stat("处罚总数", total, "list"));
        ctx.json(out);
    }

    private static Map<String, Object> stat(String label, Object value, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", String.valueOf(value));
        m.put("icon", icon);
        return m;
    }

    private void list(WebContext ctx, boolean activeOnly) throws Exception {
        PunishmentStore s = store();
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.min(200, Math.max(1, ctx.queryInt("size", 20)));
        String sort = ctx.query("sort", "id");
        if (!SORTABLE.contains(sort)) sort = "id";
        String order = "asc".equalsIgnoreCase(ctx.query("order", "desc")) ? "ASC" : "DESC";

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        boolean active = activeOnly;
        List<String> words = new ArrayList<>();
        String q = ctx.query("q", "").trim();
        if (!q.isEmpty()) {
            for (String tok : q.split("\\s+")) {
                String low = tok.toLowerCase(Locale.ROOT);
                if (low.startsWith("type:")) low = low.substring(5);
                if (low.equals("active") || low.equals("生效")) { active = true; continue; }
                Type type = switch (low) {
                    case "ban", "封禁" -> Type.BAN;
                    case "ipban", "ip" -> Type.IPBAN;
                    case "mute", "禁言" -> Type.MUTE;
                    case "warn", "警告" -> Type.WARN;
                    case "kick", "踢出" -> Type.KICK;
                    default -> null;
                };
                if (type != null) { where.append(" AND type = ?"); params.add(type.name()); }
                else words.add(tok);
            }
        }
        if (active) {
            where.append(" AND active = 1 AND (expires IS NULL OR expires > ?)");
            params.add(System.currentTimeMillis());
        }
        for (String w : words) {
            where.append(" AND (name LIKE ? OR ip LIKE ? OR reason LIKE ? OR operator LIKE ? OR uuid = ?)");
            String like = "%" + w + "%";
            params.add(like); params.add(like); params.add(like); params.add(like); params.add(w);
        }
        long total = s.db().queryOne("SELECT COUNT(*) AS c FROM " + s.table() + where, rs -> rs.getLong("c"), params.toArray()).orElse(0L);
        List<Object> p2 = new ArrayList<>(params);
        p2.add(size);
        p2.add((page - 1) * size);
        long now = System.currentTimeMillis();
        List<Map<String, Object>> items = s.db().query("SELECT " + PunishmentStore.COLUMNS + " FROM " + s.table() + where
                + " ORDER BY " + sort + " " + order + " LIMIT ? OFFSET ?", rs -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String type = rs.getString("type");
            long exp = rs.getLong("expires");
            Long expires = rs.wasNull() ? null : exp;
            boolean act = rs.getInt("active") == 1;
            String revokedBy = rs.getString("revoked_by");
            row.put("id", rs.getLong("id"));
            row.put("type", type);
            row.put("typeLabel", typeLabel(type, expires));
            row.put("uuid", rs.getString("uuid"));
            row.put("name", rs.getString("name"));
            row.put("ip", rs.getString("ip"));
            row.put("reason", rs.getString("reason"));
            row.put("operator", rs.getString("operator"));
            row.put("created", rs.getLong("created"));
            row.put("expires", expires);
            row.put("revoked_by", revokedBy);
            long ra = rs.getLong("revoked_at");
            row.put("revoked_at", rs.wasNull() ? null : ra);
            row.put("server", rs.getString("server"));
            String status;
            if ("KICK".equals(type)) status = "已执行";
            else if (act && (expires == null || expires > now)) status = "生效中";
            else if (revokedBy != null) status = "已撤销(" + revokedBy + ")";
            else status = "已过期";
            row.put("status", status);
            return row;
        }, p2.toArray());
        ctx.page(total, items);
    }

    private static String typeLabel(String type, Long expires) {
        boolean temp = expires != null;
        return switch (type) {
            case "BAN" -> temp ? "临时封禁" : "封禁";
            case "IPBAN" -> temp ? "临时IP封禁" : "IP封禁";
            case "MUTE" -> temp ? "临时禁言" : "禁言";
            case "WARN" -> "警告";
            case "KICK" -> "踢出";
            default -> type;
        };
    }

    private void revoke(WebContext ctx) throws Exception {
        long id;
        try { id = Long.parseLong(ctx.pathParam("id")); } catch (NumberFormatException e) { throw HttpException.badRequest("无效 ID"); }
        String actor = "web:" + ctx.user().username();
        boolean ok = store().revoke(id, actor);
        if (!ok) throw HttpException.notFound("该处罚不存在或已失效");
        m.audit(actor, "revoke", "#" + id);
        try { m.api().web().broadcast("ban.revoke", Map.of("id", id, "operator", actor)); } catch (Exception ignored) {}
        ctx.ok("已撤销处罚 #" + id);
    }

    private void punish(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        String player = str(body.get("player"));
        if (player.isEmpty()) throw HttpException.badRequest("请填写玩家");
        Type type;
        try { type = Type.valueOf(str(body.get("type")).toUpperCase(Locale.ROOT)); } catch (Exception e) { throw HttpException.badRequest("无效类型"); }
        String reason = str(body.get("reason"));
        boolean silent = Boolean.TRUE.equals(body.get("silent")) || "true".equalsIgnoreCase(str(body.get("silent")));
        Long duration = null;
        String durStr = str(body.get("duration"));
        if (!durStr.isEmpty() && type != Type.KICK && type != Type.WARN) {
            try { duration = Durations.parse(durStr); } catch (IllegalArgumentException e) { throw HttpException.badRequest("无法解析时长：" + durStr); }
        }
        Optional<BanModule.ReasonTemplate> tpl = reason.contains(" ") ? Optional.empty() : m.template(reason);
        if (tpl.isPresent()) {
            reason = tpl.get().reason();
            if (durStr.isEmpty() && tpl.get().hasDuration() && type != Type.KICK && type != Type.WARN) duration = tpl.get().duration();
        }
        if (type == Type.WARN) {
            String exp = m.config().getString("warn.expire", "");
            try { duration = exp == null || exp.isBlank() ? null : Durations.parse(exp); } catch (IllegalArgumentException e) { duration = null; }
        }
        final Type fType = type;
        BanModule.Target t = ctx.sync(() -> m.resolveTarget(player, fType == Type.IPBAN).orElse(null));
        if (t == null) throw HttpException.notFound("找不到玩家：" + player);
        if (type == Type.IPBAN && t.ip() == null) throw HttpException.badRequest("无法确定该玩家的 IP");
        if (type == Type.KICK && t.online() == null) throw HttpException.badRequest("玩家不在线");
        String actor = "web:" + ctx.user().username();
        Punishment p = m.execute(type, t, reason, actor, duration, silent);
        ctx.ok("已创建处罚 #" + p.id() + "（" + typeLabel(type.name(), p.expiresAt()) + " " + (t.name() != null ? t.name() : t.ip()) + "）");
    }

    private void loadConfig(WebContext ctx) throws Exception {
        Map<String, Object> out = ctx.sync(() -> {
            var c = m.config();
            Map<String, Object> o = new LinkedHashMap<>();
            for (String k : List.of("server-name", "appeal-url", "date-format", "default-reason", "warn.expire", "duration-limits.default")) {
                o.put(k, c.getString(k, ""));
            }
            for (String k : List.of("broadcast.enabled", "broadcast.api", "exempt-enabled", "warn.reset-after-action", "duration-limits.enabled")) {
                o.put(k, c.getBoolean(k));
            }
            StringBuilder actions = new StringBuilder();
            ConfigurationSection sec = c.getConfigurationSection("warn.actions");
            if (sec != null) for (String k : sec.getKeys(false)) actions.append(k).append('=').append(sec.getString(k)).append('\n');
            o.put("warn.actions", actions.toString().trim());
            o.put("muted-commands", String.join("\n", c.getStringList("muted-commands")));
            YamlConfiguration reasons = new YamlConfiguration();
            ConfigurationSection rs = c.getConfigurationSection("reasons");
            if (rs != null) for (String k : rs.getKeys(true)) if (!rs.isConfigurationSection(k)) reasons.set(k, rs.get(k));
            o.put("reasons", reasons.saveToString());
            return o;
        });
        ctx.json(out);
    }

    private void saveConfig(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        YamlConfiguration reasons = null;
        if (body.containsKey("reasons")) {
            reasons = new YamlConfiguration();
            try { reasons.loadFromString(str(body.get("reasons"))); } catch (InvalidConfigurationException e) {
                throw HttpException.badRequest("原因模板 YAML 格式错误：" + e.getMessage());
            }
        }
        final YamlConfiguration fReasons = reasons;
        String data = ctx.sync(() -> {
            var c = m.config();
            for (String k : List.of("server-name", "appeal-url", "date-format", "default-reason", "warn.expire", "duration-limits.default")) {
                if (body.containsKey(k)) c.set(k, str(body.get(k)));
            }
            for (String k : List.of("broadcast.enabled", "broadcast.api", "exempt-enabled", "warn.reset-after-action", "duration-limits.enabled")) {
                if (body.containsKey(k)) c.set(k, Boolean.TRUE.equals(body.get(k)) || "true".equalsIgnoreCase(str(body.get(k))));
            }
            if (body.containsKey("warn.actions")) {
                c.set("warn.actions", null);
                for (String line : str(body.get("warn.actions")).split("\\r?\\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String n = line.substring(0, eq).trim();
                    if (!n.matches("\\d+")) continue;
                    c.set("warn.actions." + n, line.substring(eq + 1).trim());
                }
            }
            if (body.containsKey("muted-commands")) {
                List<String> cmds = new ArrayList<>();
                for (String line : str(body.get("muted-commands")).split("[\\r\\n,]+")) {
                    String s = line.trim().replace("/", "");
                    if (!s.isEmpty()) cmds.add(s);
                }
                c.set("muted-commands", cmds);
            }
            if (fReasons != null) {
                c.set("reasons", null);
                for (String k : fReasons.getKeys(true)) if (!fReasons.isConfigurationSection(k)) c.set("reasons." + k, fReasons.get(k));
            }
            m.reloadSettings();
            return c.saveToString();
        });
        Files.writeString(new File(m.dataFolder(), "config.yml").toPath(), data, StandardCharsets.UTF_8);
        m.audit("web:" + ctx.user().username(), "config", "更新封禁配置");
        ctx.ok("配置已保存并生效");
    }

    private PunishmentStore store() {
        PunishmentStore s = m.store();
        if (s == null) throw new HttpException(503, "模块未就绪");
        return s;
    }

    private static String str(Object o) {
        if (o == null) return "";
        if (o instanceof Double d && d == Math.floor(d)) return String.valueOf(d.longValue());
        return String.valueOf(o).trim();
    }
}
