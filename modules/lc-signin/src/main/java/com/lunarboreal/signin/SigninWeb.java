package com.lunarboreal.signin;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 签到模块 Web 面板（/api/ext/signin/...）。 */
public final class SigninWeb {

    private record Cfg(String key, String path, String label, String type, String help) {}

    private static final List<Cfg> CONFIG = List.of(
            new Cfg("timezone", "timezone", "时区", "text", "如 Asia/Shanghai，每天该时区 0 点刷新"),
            new Cfg("gui_enabled", "gui-enabled", "/signin 打开日历界面", "bool", "关闭则直接签到"),
            new Cfg("broadcast", "broadcast", "签到全服广播", "bool", null),
            new Cfg("remind_join", "reminder.on-join", "登录提醒未签到", "bool", null),
            new Cfg("remind_delay", "reminder.delay-seconds", "登录提醒延迟（秒）", "number", null),
            new Cfg("remind_new_day", "reminder.on-new-day", "0 点提醒在线玩家", "bool", null),
            new Cfg("makeup_enabled", "makeup.enabled", "允许补签", "bool", null),
            new Cfg("makeup_cost", "makeup.cost", "补签花费金币", "number", "需要启用经济模块"),
            new Cfg("makeup_limit", "makeup.monthly-limit", "每月补签上限", "number", null),
            new Cfg("makeup_daily", "makeup.give-daily-reward", "补签发放每日奖励", "bool", null),
            new Cfg("makeup_milestones", "makeup.check-milestones", "补签检查里程碑", "bool", null),
            new Cfg("text_signed", "placeholder.signed", "变量：已签到文本", "text", null),
            new Cfg("text_unsigned", "placeholder.unsigned", "变量：未签到文本", "text", null)
    );

    private final SigninModule m;

    public SigninWeb(SigninModule m) {
        this.m = m;
    }

    public void register() {
        m.webPermission("signin.view", "查看签到");
        m.webPermission("signin.manage", "管理签到（重置、配置）");
        m.webPermission("signin.rewards", "编辑签到奖励（奖励命令以控制台身份执行，等同控制台权限，仅授予可信管理员）");

        m.webRoutes(r -> r
                .get("/stats", "signin.view", this::stats)
                .get("/records", "signin.view", this::records)
                .get("/top", "signin.view", this::top)
                .post("/players/{uuid}/reset", "signin.manage", this::reset)
                .get("/config", "signin.manage", this::loadConfig)
                .post("/config", "signin.manage", this::saveConfig)
                .get("/rewards", "signin.rewards", this::loadRewards)
                .post("/rewards", "signin.rewards", this::saveRewards));

        WebPanel.Table records = new WebPanel.Table("签到记录", "records")
                .rowKey("id")
                .column("id", "ID", "number")
                .column("time", "时间", "datetime")
                .column("uuid", "玩家", "player")
                .column("name", "名字")
                .column("date", "签到日期")
                .column("makeup", "补签", "bool");

        WebPanel.Table top = new WebPanel.Table("排行榜", "top")
                .rowKey("uuid")
                .searchable(false)
                .column("rank", "排名", "number")
                .column("uuid", "玩家", "player")
                .column("name", "名字")
                .column(new WebPanel.Column("streak", "连续天数", "number", true))
                .column(new WebPanel.Column("total", "累计天数", "number", true))
                .column("last_date", "最后签到")
                .rowAction(new WebPanel.Action("reset", "重置", "players/{uuid}/reset").style("danger")
                        .permission("signin.manage").confirm("确定清空该玩家的全部签到记录？"));

        WebPanel.Form form = new WebPanel.Form("签到配置", "config", "config");
        for (Cfg c : CONFIG) {
            WebPanel.Field f = new WebPanel.Field(c.key(), c.label(), c.type());
            if (c.help() != null) f.help(c.help());
            form.field(f);
        }
        // 奖励单独一个表单，需要 signin.rewards（commands 以控制台身份执行）
        WebPanel.Form rewardsForm = new WebPanel.Form("签到奖励", "rewards", "rewards")
                .field(new WebPanel.Field("rewards", "奖励配置", "code")
                        .help("按每日签到、连续签到和累计签到设置奖励。支持金币、经验、物品和控制台命令；编辑时请保留现有配置结构。"));

        m.webPanel(WebPanel.of("signin", "每日签到").icon("calendar-check").group("经济").order(20)
                .permission("signin.view")
                .section(new WebPanel.Stats("概览", "stats").refresh(60))
                .section(top)
                .section(records)
                .section(form)
                .section(rewardsForm));
    }

    private static String actor(WebContext ctx) {
        return "web:" + (ctx.user() == null ? "?" : ctx.user().username());
    }

    private static Map<String, Object> stat(String label, Object value, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", String.valueOf(value));
        m.put("icon", icon);
        return m;
    }

    private void stats(WebContext ctx) throws Exception {
        SigninStore st = m.store();
        LocalDate today = m.settings().today();
        YearMonth ym = YearMonth.from(today);
        String t = today.toString();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(stat("今日签到人数", st.countRecords(t, t, null), "calendar-check"));
        out.add(stat("本月补签次数", st.countRecords(ym.atDay(1).toString(), ym.atEndOfMonth().toString(), true), "calendar-plus"));
        out.add(stat("本月签到人次", st.countRecords(ym.atDay(1).toString(), ym.atEndOfMonth().toString(), null), "calendar"));
        out.add(stat("连续签到中玩家", st.countPlayers(true, today), "flame"));
        out.add(stat("签到过的玩家", st.countPlayers(false, today), "users"));
        ctx.json(out);
    }

    private void records(WebContext ctx) throws Exception {
        String q = ctx.query("q", "").trim();
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (var r : m.store().records(q, (page - 1) * size, size)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id());
            row.put("time", r.time());
            row.put("uuid", r.uuid().toString());
            row.put("name", r.name());
            row.put("date", r.date());
            row.put("makeup", r.makeup());
            items.add(row);
        }
        ctx.page(m.store().countRecords(q), items);
    }

    private void top(WebContext ctx) throws Exception {
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        boolean byStreak = "streak".equals(ctx.query("sort", "total"));
        LocalDate today = m.settings().today();
        var list = m.store().top(byStreak, today, (page - 1) * size, size);
        List<Map<String, Object>> items = new ArrayList<>();
        int rank = (page - 1) * size;
        for (var s : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", ++rank);
            row.put("uuid", s.uuid().toString());
            row.put("name", s.name());
            row.put("streak", s.streak());
            row.put("total", s.total());
            row.put("last_date", s.lastDate());
            items.add(row);
        }
        ctx.page(m.store().countPlayers(byStreak, today), items);
    }

    private void reset(WebContext ctx) throws Exception {
        UUID uuid;
        try {
            uuid = UUID.fromString(ctx.pathParam("uuid"));
        } catch (Exception e) {
            throw HttpException.badRequest("UUID 无效");
        }
        m.store().reset(uuid);
        ctx.sync(() -> { m.resetCached(uuid); return null; });
        m.audit(actor(ctx), "reset", uuid.toString());
        ctx.ok("已重置该玩家的签到数据");
    }

    private void loadConfig(WebContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        var cfg = m.config();
        for (Cfg c : CONFIG) out.put(c.key(), cfg.get(c.path()));
        ctx.json(out);
    }

    private void saveConfig(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        if (body.containsKey("timezone")) {
            try {
                java.time.ZoneId.of(String.valueOf(body.get("timezone")));
            } catch (Exception e) {
                throw HttpException.badRequest("时区无效");
            }
        }
        var cfg = m.config();
        for (Cfg c : CONFIG) {
            if (!body.containsKey(c.key())) continue;
            Object v = body.get(c.key());
            switch (c.type()) {
                case "bool" -> cfg.set(c.path(), Boolean.parseBoolean(String.valueOf(v)));
                case "number" -> {
                    double d;
                    try {
                        d = Double.parseDouble(String.valueOf(v));
                    } catch (NumberFormatException e) {
                        throw HttpException.badRequest(c.label() + " 不是有效数字");
                    }
                    if (d == Math.rint(d)) cfg.set(c.path(), (long) d); else cfg.set(c.path(), d);
                }
                default -> cfg.set(c.path(), v == null ? "" : String.valueOf(v));
            }
        }
        m.saveConfig();
        ctx.sync(() -> { m.onReload(); return null; });
        m.audit(actor(ctx), "config.save", "Web 修改签到配置");
        ctx.ok("配置已保存并重载");
    }

    /** 需要 signin.rewards：奖励 commands 以控制台身份执行。 */
    private void loadRewards(WebContext ctx) throws Exception {
        m.saveResource("rewards.yml", false);
        ctx.json(Map.of("rewards", Files.readString(m.rewardsFile().toPath(), StandardCharsets.UTF_8)));
    }

    private void saveRewards(WebContext ctx) throws Exception {
        Object raw = ctx.bodyMap().get("rewards");
        if (raw == null) throw HttpException.badRequest("缺少 rewards");
        String text = String.valueOf(raw);
        YamlConfiguration test = new YamlConfiguration();
        try {
            test.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw HttpException.badRequest("奖励 YAML 格式错误：" + e.getMessage());
        }
        if (!test.isConfigurationSection("rewards")) throw HttpException.badRequest("奖励 YAML 必须包含顶层 rewards 节点");
        Files.writeString(m.rewardsFile().toPath(), text, StandardCharsets.UTF_8);
        ctx.sync(() -> { m.onReload(); return null; });
        m.audit(actor(ctx), "rewards.save", "Web 修改签到奖励（rewards.yml，" + text.length() + " 字符）");
        ctx.ok("奖励已保存并重载");
    }
}
