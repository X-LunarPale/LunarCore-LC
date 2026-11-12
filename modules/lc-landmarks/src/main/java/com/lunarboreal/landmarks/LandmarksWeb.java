package com.lunarboreal.landmarks;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class LandmarksWeb {
    @FunctionalInterface interface Request { void run() throws Exception; }
    private final LandmarksModule module;
    LandmarksWeb(LandmarksModule module) { this.module = module; }
    void register() {
        module.webPermission("landmarks.view", "查看建筑地标册及投稿");
        module.webPermission("landmarks.manage", "审核建筑地标投稿与修改配置");
        module.webRoutes(routes -> routes
                .get("/stats", "landmarks.view", ctx -> handle(() -> {
                    module.requireReady();
                    ctx.json(ctx.sync(this::stats));
                }))
                .get("/entries", "landmarks.view", ctx -> handle(() -> list(ctx)))
                .get("/config", "landmarks.view", ctx -> handle(() -> ctx.json(module.settings().web())))
                .post("/config", "landmarks.manage", ctx -> handle(() -> {
                    LandmarksSettings next = LandmarksSettings.fromWeb(ctx.bodyMap());
                    await(module.saveSettings(next));
                    module.audit("web:" + ctx.user().username(), "config", "landmark settings updated");
                    ctx.ok("地标册配置已保存");
                }))
                .post("/entries/{id}/approve", "landmarks.manage",
                        ctx -> handle(() -> review(ctx, Landmark.Status.APPROVED)))
                .post("/entries/{id}/reject", "landmarks.manage",
                        ctx -> handle(() -> review(ctx, Landmark.Status.REJECTED)))
                .post("/entries/{id}/withdraw", "landmarks.manage",
                        ctx -> handle(() -> review(ctx, Landmark.Status.WITHDRAWN))));
        Map<String, String> states = Map.of("PENDING", "待审核", "APPROVED", "已公开", "REJECTED", "已驳回", "WITHDRAWN", "已撤回");
        module.webPanel(WebPanel.of("landmarks", "建筑地标册").icon("landmark").group("生存").order(55)
                .permission("landmarks.view")
                .section(new WebPanel.Stats("地标概览", "stats").refresh(30))
                .section(columns(new WebPanel.Table("待审核投稿", "entries?status=pending"), states)
                        .rowAction(new WebPanel.Action("approve", "通过审核", "entries/{id}/approve")
                                .permission("landmarks.manage").style("primary").confirm("确定公开当前版本的地标？"))
                        .rowAction(new WebPanel.Action("reject", "驳回投稿", "entries/{id}/reject")
                                .permission("landmarks.manage").style("warning")
                                .field(new WebPanel.Field("reason", "驳回原因", "textarea").required(true)
                                        .help("最多240字，投稿者可见"))))
                .section(columns(new WebPanel.Table("地标记录", "entries?status=all"), states)
                        .column("reviewer", "审核人").column("reviewNote", "审核意见")
                        .rowAction(new WebPanel.Action("withdraw", "撤回投稿", "entries/{id}/withdraw")
                                .visibleWhen("status", "PENDING", "APPROVED", "REJECTED")
                                .permission("landmarks.manage").style("danger").confirm("确定撤回此地标？撤回后将停止公开。")))
                .section(new WebPanel.Form("地标册设置", "config", "config").savePermission("landmarks.manage")
                        .field(new WebPanel.Field("enabled", "启用建筑地标册", "bool").group("展示范围"))
                        .field(new WebPanel.Field("worlds", "允许的世界", "textarea").group("展示范围")
                                .help("每行一个世界名称，留空则不开放任何世界"))
                        .field(new WebPanel.Field("max-per-player", "每位玩家的活跃投稿上限", "number")
                                .group("投稿限制").range(1, 12, 1).required(true).help("1至12条，撤回会释放名额"))));
    }
    private WebPanel.Table columns(WebPanel.Table table, Map<String, String> states) {
        return table.rowKey("id").pageSize(10).column("title", "标题")
                .column("description", "简介").column("owner", "投稿者").column("world", "世界")
                .column("coordinates", "坐标").column("status", "审核状态", "tag").valueLabels("status", states)
                .column("availability", "位置状态", "tag")
                .valueLabels("availability", Map.of("AVAILABLE", "位置有效", "HIDDEN", "已隐藏"))
                .column("updatedAt", "更新时间", "datetime").column("id", "ID");
    }
    private List<Map<String, Object>> stats() {
        var entries = module.book().entries();
        long pending = entries.stream().filter(entry -> entry.status() == Landmark.Status.PENDING).count();
        long approved = entries.stream().filter(entry -> entry.status() == Landmark.Status.APPROVED).count();
        long available = entries.stream().filter(entry -> entry.status() == Landmark.Status.APPROVED)
                .filter(entry -> module.liveClaim(entry).isPresent()).count();
        String state = module.settings().enabled() ? "已开启" : "已关闭";
        return List.of(stat("待审核", pending, "clipboard-check"),
                stat("已公开", approved, "book-open"), stat("位置有效", available, "map-pin"),
                Map.of("label", "展示状态", "labelKey", "展示状态", "value", state, "valueKey", state,
                        "icon", "settings", "hint", module.healthy() ? "存储正常" : "存储异常",
                        "hintKey", module.healthy() ? "存储正常" : "存储异常"));
    }
    private static Map<String, Object> stat(String label, long value, String icon) {
        return Map.of("label", label, "labelKey", label, "value", value, "icon", icon);
    }
    private void list(WebContext ctx) throws Exception {
        var query = LandmarkRequests.query(ctx.query("page"), ctx.query("size"), ctx.query("q"),
                ctx.query("status"), ctx.query("sort"), ctx.query("order"));
        Comparator<Landmark> comparator = query.sort().equals("updatedAt")
                ? Comparator.comparingLong(Landmark::updatedAt).thenComparingLong(Landmark::id)
                : Comparator.comparingLong(Landmark::id);
        if (query.descending()) comparator = comparator.reversed();
        var filtered = module.book().entries().stream()
                .filter(entry -> query.status() == null || entry.status() == query.status())
                .filter(entry -> query.search().isEmpty() || entry.searchable().contains(query.search()))
                .sorted(comparator).toList();
        int from = (int) Math.min(filtered.size(), (long) (query.page() - 1) * query.size());
        int to = Math.min(filtered.size(), from + query.size());
        List<Landmark> page = filtered.subList(from, to);
        var rows = ctx.sync(() -> page.stream().map(this::row).toList());
        ctx.page(filtered.size(), rows);
    }
    private Map<String, Object> row(Landmark entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entry.id()); row.put("revision", entry.revision());
        row.put("title", entry.title()); row.put("description", entry.description());
        row.put("owner", entry.ownerName()); row.put("ownerUuid", entry.owner().toString());
        row.put("landId", entry.landId()); row.put("world", entry.world());
        row.put("x", entry.x()); row.put("y", entry.y()); row.put("z", entry.z());
        row.put("coordinates", entry.x() + ", " + entry.y() + ", " + entry.z());
        row.put("status", entry.status().name());
        row.put("availability", module.liveClaim(entry).isPresent() ? "AVAILABLE" : "HIDDEN");
        row.put("createdAt", entry.createdAt()); row.put("updatedAt", entry.updatedAt());
        row.put("reviewer", entry.reviewer()); row.put("reviewNote", entry.reviewNote());
        return row;
    }
    private void review(WebContext ctx, Landmark.Status target) throws Exception {
        var request = LandmarkRequests.review(ctx.pathParam("id"), ctx.bodyMap(), target == Landmark.Status.REJECTED);
        String actor = Landmark.text(ctx.user().username(), 1, 64);
        if (target == Landmark.Status.APPROVED) ctx.sync(() -> {
            Landmark current = module.book().find(request.id());
            if (current.revision() != request.revision()) throw Landmark.problem("conflict");
            if (module.liveClaim(current).isEmpty()) throw Landmark.problem("unavailable");
            return null;
        });
        await(module.update(state -> state.moderate(request.id(), request.revision(), target, actor,
                request.reason(), System.currentTimeMillis())));
        module.audit("web:" + actor, target.name().toLowerCase(java.util.Locale.ROOT), Long.toString(request.id()));
        ctx.ok(target == Landmark.Status.APPROVED ? "地标已通过审核" :
                target == Landmark.Status.REJECTED ? "投稿已驳回" : "地标已撤回");
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }
    static void handle(Request request) throws Exception {
        try { request.run(); }
        catch (Exception error) {
            Throwable cause = LandmarksModule.unwrap(error);
            if (cause instanceof HttpException http) throw http;
            String key = cause.getMessage();
            if (cause instanceof IllegalArgumentException) {
                int status = "conflict".equals(key) ? 409 : "missing".equals(key) ? 404 : 400;
                throw new HttpException(status, switch (key == null ? "" : key) {
                    case "conflict" -> "记录已更新，请刷新后重试";
                    case "missing" -> "地标不存在";
                    case "state" -> "当前状态无法执行此操作";
                    case "reason" -> "请填写驳回原因";
                    case "text" -> "文字长度不符或含有不允许的格式";
                    case "config" -> "配置格式不正确";
                    case "query" -> "分页或筛选参数不正确";
                    case "unavailable" -> "地标位置已失效或不在展示范围";
                    default -> "请求参数不正确";
                });
            }
            throw new HttpException(503, "地标册暂不可用，请刷新确认状态后重试");
        }
    }
}
