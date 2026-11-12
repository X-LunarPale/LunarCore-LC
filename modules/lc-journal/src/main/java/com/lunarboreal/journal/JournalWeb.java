package com.lunarboreal.journal;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JournalWeb {

    private final JournalModule module;

    JournalWeb(JournalModule module) { this.module = module; }

    void register() {
        module.webPermission("journal.view", "查看探索手册");
        module.webPermission("journal.manage", "管理探索手册配置");
        module.webRoutes(routes -> routes
                .get("/stats", "journal.view", this::stats)
                .get("/records", "journal.view", this::records)
                .get("/config", "journal.view", ctx -> ctx.json(module.settings().toWeb()))
                .post("/config", "journal.manage", this::saveConfig));

        WebPanel.Table records = new WebPanel.Table("探索记录", "records").rowKey("id")
                .column("label", "条目")
                .column("category", "分类", "tag")
                .valueLabels("category", Map.of("biomes", "生物群系", "advancements", "原版进度", "fishing", "钓获收藏"))
                .column("uuid", "玩家", "player")
                .column("world", "世界")
                .column("first_seen", "首次记录", "datetime")
                .column("key", "标识");

        WebPanel.Form config = new WebPanel.Form("探索手册配置", "config", "config")
                .savePermission("journal.manage")
                .field(new WebPanel.Field("enabled", "记录探索活动", "bool").group("记录范围"))
                .field(new WebPanel.Field("worlds", "世界白名单", "textarea").group("记录范围")
                        .help("每行一个世界名称；留空时暂停记录所有世界。"))
                .field(new WebPanel.Field("sample_seconds", "生物群系采样间隔（秒）", "number")
                        .range(2, 60, 1).group("记录范围"))
                .field(new WebPanel.Field("record_biomes", "记录首次生物群系", "bool").group("收藏分类"))
                .field(new WebPanel.Field("record_advancements", "记录原版进度", "bool").group("收藏分类"))
                .field(new WebPanel.Field("record_fishing", "记录钓获物品种类", "bool").group("收藏分类"))
                .field(new WebPanel.Field("notify_discoveries", "首次发现时私聊通知", "bool").group("通知"));

        String note = "仅记录启用后生存或冒险模式的首次发现，不回填历史日期。更改记录范围会保留已有收藏。";
        module.webPanel(WebPanel.of("journal", "探索手册").group("生存").icon("book-open").order(40)
                .permission("journal.view")
                .section(new WebPanel.Stats("概览", "stats").refresh(30))
                .section(records)
                .section(config)
                .section(new WebPanel.Text("记录说明", note).markdownKey(note)));
    }

    private void stats(WebContext ctx) throws Exception {
        requireReady();
        var stats = module.repository().stats(null);
        ctx.json(List.of(
                stat("记录总数", stats.total(), "book-open"),
                stat("参与玩家", stats.players(), "users"),
                stat("生物群系记录", stats.biomes(), "map"),
                stat("原版进度记录", stats.advancements(), "award"),
                stat("钓获收藏记录", stats.fishing(), "fish"),
                stat("待写入记录", module.pendingCount(), "clock")));
    }

    static Map<String, Object> stat(String label, Object value, String icon) {
        return Map.of("label", label, "labelKey", label, "value", String.valueOf(value), "icon", icon);
    }

    private void records(WebContext ctx) throws Exception {
        requireReady();
        int page = positive(ctx.query("page"), 1, 1_000_000, "page");
        int size = positive(ctx.query("size"), 20, 100, "size");
        String query = ctx.query("q", "");
        if (query.length() > 100) throw HttpException.badRequest("搜索内容不能超过 100 字符");
        JournalEntry.Category category = null;
        if (ctx.query("category") != null && !ctx.query("category").isEmpty()) {
            try { category = JournalEntry.Category.parse(ctx.query("category")); }
            catch (IllegalArgumentException error) { throw HttpException.badRequest("收藏分类无效"); }
        }
        var result = module.repository().page(null, category, query.trim(), (long) (page - 1) * size, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JournalEntry entry : result.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.id());
            row.put("uuid", entry.uuid().toString());
            row.put("player", entry.playerName());
            row.put("category", entry.category().id());
            row.put("key", entry.key());
            row.put("label", entry.label());
            row.put("world", entry.world());
            row.put("first_seen", entry.firstSeen());
            items.add(row);
        }
        ctx.page(result.total(), items);
    }

    static int positive(String raw, int fallback, int maximum, String field) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]{0,8}")) throw HttpException.badRequest(field + " 必须为有效正整数");
        int value = Integer.parseInt(raw);
        if (value > maximum) throw HttpException.badRequest(field + " 超出允许范围");
        return value;
    }

    private void saveConfig(WebContext ctx) throws Exception {
        try { module.updateSettings(ctx.bodyMap()); }
        catch (IllegalArgumentException error) { throw HttpException.badRequest(error.getMessage()); }
        module.audit("web:" + ctx.user().username(), "config.save", "更新探索手册配置");
        ctx.ok("探索手册配置已保存");
    }

    private void requireReady() {
        if (!module.ready()) throw new HttpException(503, "探索手册数据正在准备中");
    }
}
