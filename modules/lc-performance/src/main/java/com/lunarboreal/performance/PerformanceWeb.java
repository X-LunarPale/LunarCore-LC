package com.lunarboreal.performance;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;
import com.lunarboreal.performance.lagfixer.EmbeddedLagFixer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PerformanceWeb {
    private final PerformanceModule m;
    PerformanceWeb(PerformanceModule m) { this.m = m; }
    void register() {
        m.webPermission("performance.view", "查看性能指标");
        m.webPermission("performance.manage", "修改性能采样间隔");
        m.webPermission("performance.optimize", "管理内置优化开关（可能改变玩法或删除实体）");
        m.webPermission("performance.lagfixer.monitor", "请求 LagFixer 只读 monitor 输出到服务器控制台");
        m.webRoutes(r -> r.get("/snapshot", "performance.view", c -> c.json(m.snapshot()))
                .get("/stats", "performance.view", this::stats)
                .get("/worlds", "performance.view", c -> {
                    var worlds = (List<?>) m.snapshot().get("worlds");
                    int size = Math.max(1, Math.min(200, c.queryInt("size", 20)));
                    long start = (Math.max(1L, c.queryInt("page", 1)) - 1L) * size;
                    int from = (int) Math.min(worlds.size(), start);
                    c.page(worlds.size(), worlds.subList(from, Math.min(worlds.size(), from + size)));
                })
                .get("/embedded/list", "performance.view", this::embeddedList)
                .post("/embedded/toggle", "performance.optimize", this::toggleEmbedded)
                .get("/embedded/switches", "performance.view", c -> c.json(c.sync(() -> EmbeddedSwitches.values(m.config()))))
                .post("/embedded/switches", "performance.optimize", this::saveSwitches)
                .post("/embedded/enable-all", "performance.optimize", c -> batchEmbedded(c, true))
                .post("/embedded/disable-all", "performance.optimize", c -> batchEmbedded(c, false))
                .get("/config", "performance.view", c -> c.json(Map.of("sampleSeconds", m.settings().sampleSeconds())))
                .post("/config", "performance.manage", this::saveSampling)
                .get("/lagfixer", "performance.view", c -> c.json(c.sync(() -> m.bridge().status())))
                .post("/lagfixer/monitor", "performance.lagfixer.monitor", this::monitor));
        m.webPanel(WebPanel.of("performance", "性能").icon("activity").group("管理").order(40)
                .permission("performance.view")
                .section(new WebPanel.Text("内置优化管理", "保存后应用。会重启优化任务，关闭垃圾箱与回收箱并退还剩余物品。"))
                .section(new WebPanel.Text("状态说明", "未触发功能时，操作数为零。操作数在模块停止后清空，不代表性能提升。"))
                .section(new WebPanel.Stats("性能快照", "stats").refresh(5))
                .section(switchForm())
                .section(new WebPanel.Table("内置功能总览", "embedded/list").rowKey("id").searchable(false).refresh(5)
                        .column("name", "功能").column("enabled", "已启用", "bool")
                        .column("operations", "累计操作", "number")
                        .toolbarAction(new WebPanel.Action("enable-all", "全部启用", "embedded/enable-all").permission("performance.optimize").style("primary"))
                        .toolbarAction(new WebPanel.Action("disable-all", "全部关闭", "embedded/disable-all").permission("performance.optimize")))
                .section(new WebPanel.Table("世界（最近采样）", "worlds").searchable(false).refresh(5)
                        .column("name", "世界").column("entities", "已加载实体", "number")
                        .column("chunks", "已加载区块", "number").column("players", "玩家", "number")
                        .toolbarAction(new WebPanel.Action("monitor", "LagFixer monitor → 控制台", "lagfixer/monitor")
                                .permission("performance.lagfixer.monitor")))
                .section(new WebPanel.Form("采样配置", "config", "config").savePermission("performance.manage")
                        .field(new WebPanel.Field("sampleSeconds", "采样间隔（秒）", "number").range(1, 300, 1)
                                .help("保存后重启优化任务并退还箱内剩余物品。掉落物清理需单独开启。"))));
    }
    private WebPanel.Form switchForm() {
        var form = new WebPanel.Form("功能开关", "embedded/switches", "embedded/switches")
                .savePermission("performance.optimize").submitLabel("应用功能开关");
        for (var feature : EmbeddedSwitches.ALL) form.field(new WebPanel.Field(feature.key(), feature.label(), "bool"));
        return form;
    }

    private void saveSwitches(WebContext c) throws Exception {
        Map<String, Object> body = c.bodyMap();
        c.sync(() -> { requireActive(); saveAndReload(EmbeddedSwitches.changes(body, m.config())); return null; });
        m.audit(actor(c), "embedded.switches", "updated=" + body.size());
        c.ok("功能开关已应用");
    }
    private void batchEmbedded(WebContext c, boolean enabled) throws Exception {
        Map<String, Object> body = c.bodyMap();
        if (body != null && body.keySet().stream().anyMatch(k -> !k.equals("row")))
            throw HttpException.badRequest("一键操作不接受额外配置");
        if (body != null && body.containsKey("row") && !(body.get("row") instanceof Map<?, ?>))
            throw HttpException.badRequest("操作数据格式不正确");
        c.sync(() -> { requireActive(); saveAndReload(EmbeddedSwitches.batch(enabled)); return null; });
        m.audit(actor(c), "embedded.batch", "enabled=" + enabled);
        c.ok(enabled ? "全部内置功能已启用" : "全部内置功能已关闭");
    }
    private void embeddedList(WebContext c) throws Exception {
        List<Map<String, Object>> rows = c.sync(() -> {
            requireActive();
            return m.embedded().status().stream().map(row -> {
                Map<String, Object> translated = new java.util.LinkedHashMap<>(row);
                translated.put("name", EmbeddedSwitches.label(String.valueOf(row.get("id"))));
                return translated;
            }).toList();
        });
        int size = Math.max(1, Math.min(100, c.queryInt("size", 20)));
        long start = (Math.max(1L, c.queryInt("page", 1)) - 1L) * size;
        int from = (int) Math.min(rows.size(), start);
        c.page(rows.size(), rows.subList(from, Math.min(rows.size(), from + size)));
    }

    private void toggleEmbedded(WebContext c) throws Exception {
        Map<String, Object> body = c.bodyMap();
        if (body == null || body.keySet().stream().anyMatch(k -> !List.of("id", "enabled", "row").contains(k))
                || (body.containsKey("row") && !(body.get("row") instanceof Map<?, ?>))) {
            throw HttpException.badRequest("仅允许功能标识、启用状态及面板行数据");
        }
        if (!(body.get("id") instanceof String id) || !EmbeddedLagFixer.ids().contains(id))
            throw HttpException.badRequest("未知内置优化模块");
        if (!(body.get("enabled") instanceof Boolean enabled))
            throw HttpException.badRequest("启用状态必须为开关值");
        c.sync(() -> {
            requireActive();
            saveAndReload("embedded." + id + ".enabled", enabled);
            return null;
        });
        m.audit(actor(c), "embedded.toggle", id + " enabled=" + enabled);
        c.ok(EmbeddedSwitches.label(id) + (enabled ? "已启用" : "已关闭"));
    }

    private void stats(WebContext c) throws Exception {
        // status() reads Bukkit-owned module state, so capture it on the server thread.
        List<Map<String, Object>> embedded = c.sync(() -> m.embedded().status());
        c.json(formatStats(m.snapshot(), embedded));
    }

    static List<Map<String, Object>> formatStats(Map<String, Object> s, List<Map<String, Object>> embedded) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(stat("TPS", decimal(s.get("tps"), 2, ""), "最近 1 分钟平均值，目标 20.00", "activity"));
        out.add(stat("平均 tick 耗时", decimal(s.get("mspt"), 2, " ms"), "MSPT；低于 50 ms 可维持 20 TPS", "timer"));
        out.add(stat("已用堆内存", memory(s.get("memoryUsedBytes")), "JVM 已分配堆中的实际用量", "memory-stick"));
        out.add(stat("最大堆内存", memory(s.get("memoryMaxBytes")), "JVM 可使用的堆内存上限", "database"));
        Object sampled = s.get("sampledAt");
        String time = sampled instanceof Number n && n.longValue() > 0
                ? java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", java.util.Locale.SIMPLIFIED_CHINESE)
                    .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(n.longValue()))
                : "等待采样";
        out.add(stat("最近采样", time, "服务器时区：" + java.time.ZoneId.systemDefault().getId(), "clock"));
        Map<?, ?> cleaner = s.get("cleaner") instanceof Map<?, ?> map ? map : Map.of();
        out.add(stat("掉落物清理", cleaner.isEmpty() ? "等待采样" : Boolean.TRUE.equals(cleaner.get("enabled")) ? "已开启" : "已关闭",
                "跟踪 " + count(cleaner.get("tracked")) + " · 已清理 " + count(cleaner.get("removed"))
                        + " · 容量溢出 " + count(cleaner.get("overflow")), "trash"));
        Map<?, ?> bridge = s.get("lagfixer") instanceof Map<?, ?> map ? map : Map.of();
        String bridgeState = bridge.isEmpty() ? "等待采样" : !Boolean.TRUE.equals(bridge.get("installed")) ? "未安装外部插件"
                : !Boolean.TRUE.equals(bridge.get("enabled")) ? "外部插件未启用"
                : Boolean.TRUE.equals(bridge.get("bridgeAvailable")) ? "桥接可用" : "桥接不可用";
        String version = Boolean.TRUE.equals(bridge.get("installed")) ? String.valueOf(bridge.get("version")) : "未安装";
        out.add(stat("外部 LagFixer 桥接", bridgeState, "外部版本：" + version + "；只在检测到插件及其命令时提供固定 monitor 桥接", "plug"));
        long enabled = embedded.stream().filter(row -> Boolean.TRUE.equals(row.get("enabled"))).count();
        out.add(stat("内置优化配置", enabled + " / " + embedded.size(), "已启用配置数；不代表正在处理或已提升性能", "settings"));
        return List.copyOf(out);
    }

    private static Map<String, Object> stat(String label, String value, String hint, String icon) {
        return Map.of("label", label, "value", value, "hint", hint, "icon", icon);
    }
    private static String decimal(Object value, int digits, String unit) {
        return value instanceof Number n && Double.isFinite(n.doubleValue())
                ? String.format(java.util.Locale.SIMPLIFIED_CHINESE, "%,." + digits + "f", n.doubleValue()) + unit : "等待采样";
    }
    private static String memory(Object value) {
        return value instanceof Number n ? decimal(n.doubleValue() / (1024 * 1024), 1, " MiB") : "等待采样";
    }
    private static String count(Object value) {
        return value instanceof Number n ? String.format(java.util.Locale.SIMPLIFIED_CHINESE, "%,d", n.longValue()) : "—";
    }
    static int sampleSeconds(Object raw) {
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 1 || n.doubleValue() > 300)
            throw HttpException.badRequest("采样间隔必须是1–300的整数秒");
        return n.intValue();
    }
    private void saveSampling(WebContext c) throws Exception {
        Map<String, Object> body = c.bodyMap();
        if (body == null || body.size() != 1 || !body.containsKey("sampleSeconds"))
            throw HttpException.badRequest("仅允许修改采样间隔");
        int seconds = sampleSeconds(body.get("sampleSeconds"));
        c.sync(() -> {
            requireActive();
            saveAndReload("sampling.interval-seconds", seconds);
            return null;
        });
        m.audit(actor(c), "sampling", "interval-seconds=" + seconds);
        c.ok("采样间隔已更新");
    }
    private void monitor(WebContext c) throws Exception {
        if (c.body() != null && c.body().length() > 4096) throw HttpException.badRequest("请求过大");
        boolean ok = c.sync(() -> { requireActive(); return m.bridge().monitor(); });
        if (!ok) throw HttpException.badRequest("未检测到已启用的 LagFixer 插件及其自有 monitor 命令");
        m.audit(actor(c), "lagfixer-monitor", "fixed read-only monitor command; output=console");
        c.ok("LagFixer monitor 已输出到服务器控制台");
    }
    /** Called on the Bukkit thread; saveConfig() would swallow persistence failures. */
    private void saveAndReload(String path, Object value) throws Exception {
        saveAndReload(Map.of(path, value));
    }
    private void saveAndReload(Map<String, Object> changes) throws Exception {
        var config = m.config();
        Map<String, Object> previous = new java.util.LinkedHashMap<>();
        changes.forEach((path, value) -> { previous.put(path, config.isSet(path) ? config.get(path) : null); config.set(path, value); });
        try {
            persist(config);
        } catch (java.io.IOException failure) {
            previous.forEach(config::set);
            m.logger().log(java.util.logging.Level.SEVERE, "Web config save failed", failure);
            throw new HttpException(500, "配置写入失败，内存修改已撤回；请检查服务器日志和文件权限");
        }
        try {
            m.onReload();
        } catch (Exception failure) {
            previous.forEach(config::set);
            try {
                persist(config);
                m.onReload();
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                m.logger().log(java.util.logging.Level.SEVERE, "Web config reload and rollback failed", failure);
                throw new HttpException(500, "应用失败，恢复原配置时出现错误；请检查服务器日志");
            }
            m.logger().log(java.util.logging.Level.SEVERE, "Web config reload failed; previous configuration restored", failure);
            throw new HttpException(500, "应用失败，已恢复修改前的配置");
        }
    }

    private void persist(org.bukkit.configuration.file.FileConfiguration config) throws java.io.IOException {
        java.nio.file.Path target = new java.io.File(m.dataFolder(), "config.yml").toPath();
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(target.getParent(), "performance-", ".tmp");
        try {
            java.nio.file.Files.writeString(temporary, config.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
            // Do not truncate the existing file if writing or replacing the new configuration fails.
            java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    private void requireActive() { if (!m.active()) throw HttpException.badRequest("性能模块已停用"); }
    private static String actor(WebContext c) { return "web:" + (c.user() == null ? "?" : c.user().username()); }
}
