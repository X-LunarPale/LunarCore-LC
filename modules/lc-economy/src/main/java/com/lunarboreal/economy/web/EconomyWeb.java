package com.lunarboreal.economy.web;

import com.lunarboreal.api.service.EconomyService.Result;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
import com.lunarboreal.economy.EconomyModule;
import com.lunarboreal.economy.EconomyServiceImpl;
import com.lunarboreal.economy.EconomySettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 经济模块 Web 面板与 REST 路由（/api/ext/economy/...）。 */
public final class EconomyWeb {

    /** 表单字段 key → config 路径与类型。 */
    private record Cfg(String key, String path, String label, String type, String help, String group) {}

    private static final List<Cfg> CONFIG = List.of(
            new Cfg("currency_singular", "currency.singular", "货币单数名", "text", "金额为 1 时显示", "货币显示"),
            new Cfg("currency_plural", "currency.plural", "货币复数名", "text", null, "货币显示"),
            new Cfg("currency_decimals", "currency.decimals", "小数位数", "number", "0-8", "货币显示"),
            new Cfg("currency_format", "currency.format", "显示格式", "text", "{amount} 数字，{currency} 货币名", "货币显示"),
            new Cfg("currency_grouping", "currency.grouping", "千分位分隔", "bool", null, "货币显示"),
            new Cfg("account_initial", "account.initial-balance", "初始余额", "number", null, "账户规则"),
            new Cfg("account_max", "account.max-balance", "最大余额", "number", "<= 0 表示不限", "账户规则"),
            new Cfg("storage_flush", "storage.flush-interval", "刷盘间隔（秒）", "number", "重载模块后生效", "刷新与分页"),
            new Cfg("storage_top", "storage.top-refresh", "排行榜刷新间隔（秒）", "number", "重载模块后生效", "刷新与分页"),
            new Cfg("pay_min", "pay.min-amount", "最小转账金额", "number", null, "转账规则"),
            new Cfg("pay_fee", "pay.fee-percent", "转账手续费 %", "number", "0 为免手续费", "转账规则"),
            new Cfg("pay_min_fee", "pay.min-fee", "最低手续费", "number", null, "转账规则"),
            new Cfg("pay_confirm", "pay.confirm-threshold", "转账确认阈值", "number", "<= 0 不需要确认", "转账规则"),
            new Cfg("pay_confirm_timeout", "pay.confirm-timeout", "确认有效期（秒）", "number", null, "转账规则"),
            new Cfg("baltop_size", "baltop.page-size", "排行榜每页条数", "number", null, "刷新与分页"),
            new Cfg("log_size", "log.page-size", "流水每页条数", "number", null, "刷新与分页"),
            new Cfg("vault_enabled", "vault.enabled", "启用 Vault 桥接", "bool", null, "Vault 桥接"),
            new Cfg("vault_name", "vault.name", "Vault 显示名", "text", null, "Vault 桥接")
    );
    private static final List<String> CONFIG_GROUPS = List.of("货币显示", "账户规则", "转账规则", "刷新与分页", "Vault 桥接");

    private final EconomyModule m;

    public EconomyWeb(EconomyModule m) {
        this.m = m;
    }

    public void register() {
        m.webPermission("economy.view", "查看经济");
        m.webPermission("economy.manage", "管理经济（修改余额、配置）");

        m.webRoutes(r -> r
                .get("/stats", "economy.view", this::stats)
                .get("/accounts", "economy.view", this::accounts)
                .post("/accounts/{uuid}/give", "economy.manage", ctx -> modify(ctx, "give"))
                .post("/accounts/{uuid}/take", "economy.manage", ctx -> modify(ctx, "take"))
                .post("/accounts/{uuid}/set", "economy.manage", ctx -> modify(ctx, "set"))
                .post("/accounts/{uuid}/reset", "economy.manage", ctx -> modify(ctx, "reset"))
                .post("/accounts/create", "economy.manage", this::create)
                .get("/transactions", "economy.view", this::transactions)
                .get("/config", "economy.manage", this::loadConfig)
                .post("/config", "economy.manage", this::saveConfig));

        WebPanel.Field amount = new WebPanel.Field("amount", "金额", "number").required(true);
        WebPanel.Table accounts = new WebPanel.Table("账户", "accounts")
                .rowKey("uuid")
                .column(new WebPanel.Column("rank", "排名", "number", true))
                .column(new WebPanel.Column("uuid", "玩家", "player", false))
                .column(new WebPanel.Column("name", "名字", "text", true))
                .column(new WebPanel.Column("balance", "余额", "money", true))
                .column(new WebPanel.Column("formatted", "显示", "text", false))
                .column(new WebPanel.Column("updated", "最后变动", "datetime", true))
                .rowAction(new WebPanel.Action("give", "增加", "accounts/{uuid}/give").style("primary")
                        .permission("economy.manage").field(amount)
                        .field(new WebPanel.Field("reason", "原因", "text")))
                .rowAction(new WebPanel.Action("take", "扣除", "accounts/{uuid}/take").style("warning")
                        .permission("economy.manage").field(amount)
                        .field(new WebPanel.Field("reason", "原因", "text")))
                .rowAction(new WebPanel.Action("set", "设置余额", "accounts/{uuid}/set")
                        .permission("economy.manage").field(amount)
                        .field(new WebPanel.Field("reason", "原因", "text")))
                .rowAction(new WebPanel.Action("reset", "重置", "accounts/{uuid}/reset").style("danger")
                        .permission("economy.manage").confirm("确定将该账户重置为初始余额？"))
                .toolbarAction(new WebPanel.Action("create", "给玩家加钱", "accounts/create").style("primary")
                        .permission("economy.manage")
                        .field(new WebPanel.Field("player", "玩家", "player").required(true))
                        .field(new WebPanel.Field("amount", "金额", "number").required(true))
                        .field(new WebPanel.Field("reason", "原因", "text")));

        WebPanel.Table txs = new WebPanel.Table("流水", "transactions")
                .rowKey("id")
                .column("id", "ID", "number")
                .column("time", "时间", "datetime")
                .column("uuid", "玩家", "player")
                .column("name", "名字")
                .column("amount", "变动", "money")
                .column("balance", "余额", "money")
                .column("reason", "原因")
                .column("operator", "操作者", "tag");

        WebPanel.Form form = new WebPanel.Form("经济配置", "config", "config");
        for (String group : CONFIG_GROUPS) {
            for (Cfg c : CONFIG) {
                if (!group.equals(c.group())) continue;
                WebPanel.Field f = new WebPanel.Field(c.key(), c.label(), c.type()).group(group);
                if (c.help() != null) f.help(c.help());
                form.field(f);
            }
        }

        m.webPanel(WebPanel.of("economy", "经济系统").icon("coins").group("经济").order(10)
                .permission("economy.view")
                .section(new WebPanel.Stats("概览", "stats").refresh(30))
                .section(accounts)
                .section(txs)
                .section(form));
    }

    private static String actor(WebContext ctx) {
        return "web:" + (ctx.user() == null ? "?" : ctx.user().username());
    }

    private void stats(WebContext ctx) throws Exception {
        EconomyServiceImpl eco = m.service();
        long start = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        BigDecimal[] today = eco.txSince(start);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(stat("货币总量", eco.format(eco.totalMoney()), "coins"));
        out.add(stat("账户数", String.valueOf(eco.accountCount()), "users"));
        out.add(stat("今日流水笔数", today[0].toPlainString(), "receipt"));
        out.add(stat("今日流入总额", eco.format(today[1]), "trending-up"));
        ctx.json(out);
    }

    private static Map<String, Object> stat(String label, String value, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        m.put("icon", icon);
        return m;
    }

    private void accounts(WebContext ctx) {
        EconomyServiceImpl eco = m.service();
        String q = ctx.query("q", "").toLowerCase(Locale.ROOT);
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        String sort = ctx.query("sort", "balance");
        boolean asc = "asc".equalsIgnoreCase(ctx.query("order", "desc"));

        List<EconomyServiceImpl.Account> list = new ArrayList<>();
        for (EconomyServiceImpl.Account a : eco.allAccounts()) {
            if (q.isEmpty() || (a.name() != null && a.name().toLowerCase(Locale.ROOT).contains(q))
                    || a.uuid().toString().startsWith(q)) {
                list.add(a);
            }
        }
        Comparator<EconomyServiceImpl.Account> cmp = switch (sort) {
            case "name" -> Comparator.comparing(a -> a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT));
            case "updated" -> Comparator.comparingLong(EconomyServiceImpl.Account::updated);
            case "rank" -> Comparator.comparingInt(a -> -eco.rank(a.uuid()));
            default -> Comparator.comparing(EconomyServiceImpl.Account::balance);
        };
        if (!asc) cmp = cmp.reversed();
        list.sort(cmp);
        int from = Math.min(list.size(), (page - 1) * size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (EconomyServiceImpl.Account a : list.subList(from, Math.min(list.size(), from + size))) {
            Map<String, Object> row = new LinkedHashMap<>();
            BigDecimal bal = a.balance();
            row.put("uuid", a.uuid().toString());
            row.put("name", a.name());
            row.put("balance", bal);
            row.put("formatted", eco.format(bal));
            row.put("rank", eco.rank(a.uuid()));
            row.put("updated", a.updated());
            items.add(row);
        }
        ctx.page(list.size(), items);
    }

    private BigDecimal amount(WebContext ctx) {
        Object v = ctx.bodyMap().get("amount");
        BigDecimal a = v == null ? null : m.settings().parseAmount(String.valueOf(v));
        if (a == null || a.signum() < 0) throw HttpException.badRequest("金额无效");
        return a;
    }

    private void modify(WebContext ctx, String op) {
        EconomyServiceImpl eco = m.service();
        UUID uuid;
        try {
            uuid = UUID.fromString(ctx.pathParam("uuid"));
        } catch (Exception e) {
            throw HttpException.badRequest("UUID 无效");
        }
        if (!eco.hasAccount(uuid)) throw HttpException.notFound("账户不存在");
        BigDecimal amount = op.equals("reset") ? m.settings().initialBalance : amount(ctx);
        apply(ctx, uuid, op, amount);
    }

    private void create(WebContext ctx) {
        EconomyServiceImpl eco = m.service();
        Object p = ctx.bodyMap().get("player");
        String input = p == null ? "" : String.valueOf(p).trim();
        UUID uuid;
        try {
            uuid = UUID.fromString(input);
        } catch (IllegalArgumentException e) {
            uuid = eco.resolve(input);
        }
        if (uuid == null) throw HttpException.notFound("找不到玩家：" + input);
        apply(ctx, uuid, "give", amount(ctx));
    }

    private void apply(WebContext ctx, UUID uuid, String op, BigDecimal amount) {
        EconomyServiceImpl eco = m.service();
        Object rs = ctx.bodyMap().get("reason");
        String reason = rs == null || String.valueOf(rs).isBlank() ? "Web 管理 " + op : String.valueOf(rs);
        String actor = actor(ctx);
        Result r = switch (op) {
            case "give" -> eco.deposit(uuid, amount, reason, actor);
            case "take" -> eco.withdraw(uuid, amount, reason, actor);
            default -> eco.set(uuid, amount, reason, actor);
        };
        if (!r.success()) throw HttpException.badRequest(m.errorText(r.error()));
        String name = eco.nameOf(uuid);
        m.audit(actor, "eco." + op, name + " " + amount.toPlainString() + " → " + r.balance().toPlainString() + "（" + reason + "）");
        BigDecimal bal = r.balance();
        m.runTask(() -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) m.msg(online, "eco.notify", "amount", eco.format(bal));
        });
        ctx.ok("已更新 " + name + " 的余额：" + eco.format(bal));
    }

    private void transactions(WebContext ctx) throws Exception {
        EconomyServiceImpl eco = m.service();
        String q = ctx.query("q", "").trim();
        int page = Math.max(1, ctx.queryInt("page", 1));
        int size = Math.max(1, Math.min(200, ctx.queryInt("size", 20)));
        UUID player = null;
        if (!q.isEmpty()) {
            UUID u = eco.resolve(q);
            if (u != null && eco.hasAccount(u)) { player = u; q = ""; }
        }
        List<EconomyServiceImpl.Tx> list = eco.queryTx(player, q, (page - 1) * size, size);
        long total = eco.countTx(player, q);
        List<Map<String, Object>> items = new ArrayList<>();
        for (EconomyServiceImpl.Tx t : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.id());
            row.put("time", t.time());
            row.put("uuid", t.uuid().toString());
            row.put("name", t.name());
            row.put("amount", t.change());
            row.put("balance", t.balance());
            row.put("reason", t.reason());
            row.put("operator", t.operator());
            items.add(row);
        }
        ctx.page(total, items);
    }

    private void loadConfig(WebContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        var cfg = m.config();
        for (Cfg c : CONFIG) {
            Object v = cfg.get(c.path());
            if ("number".equals(c.type()) && v != null) {
                out.put(c.key(), EconomySettings.dec(String.valueOf(v)));
            } else {
                out.put(c.key(), v);
            }
        }
        ctx.json(out);
    }

    private void saveConfig(WebContext ctx) throws Exception {
        Map<String, Object> body = ctx.bodyMap();
        var cfg = m.config();
        for (Cfg c : CONFIG) {
            if (!body.containsKey(c.key())) continue;
            Object v = body.get(c.key());
            switch (c.type()) {
                case "bool" -> cfg.set(c.path(), Boolean.parseBoolean(String.valueOf(v)));
                case "number" -> {
                    BigDecimal d;
                    try {
                        d = new BigDecimal(String.valueOf(v).trim());
                    } catch (NumberFormatException e) {
                        throw HttpException.badRequest(c.label() + " 不是有效数字");
                    }
                    if (d.stripTrailingZeros().scale() <= 0) {
                        cfg.set(c.path(), d.longValueExact());
                    } else {
                        cfg.set(c.path(), d.doubleValue());
                    }
                }
                default -> cfg.set(c.path(), v == null ? "" : String.valueOf(v));
            }
        }
        m.saveConfig();
        ctx.sync(() -> { m.onReload(); return null; });
        m.audit(actor(ctx), "config.save", "Web 修改经济配置");
        ctx.ok("配置已保存并重载");
    }
}
