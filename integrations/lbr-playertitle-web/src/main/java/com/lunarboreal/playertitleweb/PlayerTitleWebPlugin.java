package com.lunarboreal.playertitleweb;

import com.lunarboreal.api.LBR;
import com.lunarboreal.api.web.*;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independently authored bridge. PlayerTitle remains a separately installed proprietary plugin. */
public final class PlayerTitleWebPlugin extends JavaPlugin implements Listener {
    static final String NS = "playertitle", VIEW = "playertitle.view", MANAGE = "playertitle.manage";
    private PublicTitleApi titleApi;
    private CurrentTitleCache currentTitles;
    private volatile boolean registered;
    private final Set<Integer> tracked = new LinkedHashSet<>();
    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        loadTracked();
        register();
    }
    private void loadTracked() {
        Path path = getDataFolder().toPath().resolve("tracked-ids.txt");
        if (!Files.exists(path)) return;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                int id = Integer.parseInt(line.trim());
                if (id > 0 && tracked.size() < 1000) tracked.add(id);
            }
        } catch (Exception e) { getLogger().warning("Unable to load tracked IDs: " + e.getMessage()); }
    }
    private void track(int id) {
        if (tracked.contains(id) || tracked.size() >= 1000) return;
        tracked.add(id);
        Path target = getDataFolder().toPath().resolve("tracked-ids.txt");
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "tracked-", ".tmp");
            try {
                Files.write(temp, tracked.stream().map(Object::toString).toList(), StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        } catch (Exception e) { getLogger().warning("Verified title remains usable, but tracking persistence failed: " + e.getMessage()); }
    }
    private void register() {
        if (!LBR.isLoaded()) return;
        Plugin target = Bukkit.getPluginManager().getPlugin("PlayerTitle");
        if (target == null || !target.isEnabled()) return;
        try { titleApi = new PublicTitleApi(target.getClass().getClassLoader()); }
        catch (ReflectiveOperationException | LinkageError e) { unregister(); getLogger().warning("PlayerTitle is installed, but required public API capabilities are unavailable: " + e); return; }
        WebRegistry web = LBR.get().web();
        web.unregisterAll(this);
        web.registerPermission(VIEW, "查看 PlayerTitle 商店/已验证目录与玩家持有记录", this);
        web.registerPermission(MANAGE, "创建、编辑、授予、撤销和佩戴 PlayerTitle 称号", this);
        web.routes(NS, this, routes -> {
            routes.get("/health", VIEW, ctx -> ctx.json(Map.of("available", registered, "version", target.getDescription().getVersion(), "catalogScope", "shop + verified tracked IDs", "createSupported", true)));
            routes.post("/titles/{id}/delete", MANAGE, ctx -> {
                int id = pathId(ctx);
                String message = ctx.sync(() -> {
                    requireApi(); var before = existing(id); titleApi.remove(id); var after = titleApi.find(id);
                    audit(ctx, "delete", "id=" + id + "; before=" + before + "; after=" + after);
                    return after.isEmpty() ? "删除 API 已成功，已核验称号不存在" : "删除 API 已返回成功；读回仍存在，请刷新核验";
                });
                ctx.ok(message);
            });
            routes.get("/catalog", VIEW, ctx -> page(ctx, ctx.sync(() -> {
                requireApi();
                Map<Integer, Map<String, Object>> rows = new LinkedHashMap<>();
                for (Map<String, Object> row : titleApi.shop()) {
                    if (row.get("id") instanceof Integer id && id > 0) { row.put("source", "商店公开API"); rows.put(id, row); }
                }
                for (int id : List.copyOf(tracked)) titleApi.find(id).ifPresent(row -> { row.put("source", "已验证追踪ID"); rows.putIfAbsent(id, row); });
                return new ArrayList<>(rows.values());
            })));
            routes.post("/lookup", VIEW, ctx -> {
                int id = checked(() -> Inputs.integer(ctx.bodyMap(), "id", 1, Integer.MAX_VALUE));
                ctx.ok(ctx.sync(() -> {
                    requireApi(); Map<String, Object> row = titleApi.find(id).orElseThrow(() -> HttpException.badRequest("该 ID 不存在"));
                    track(id); return "已查验并加入目录：" + row.get("titleName");
                }));
            });
            routes.get("/players", VIEW, ctx -> {
                String query = Optional.ofNullable(ctx.query("q")).orElse("").trim();
                if (query.isEmpty()) { ctx.page(0, List.of()); return; }
                List<Map<String, Object>> owned = ctx.sync(() -> {
                    requireApi(); UUID uuid = resolve(query);
                    List<Map<String, Object>> rows = titleApi.owned(uuid);
                    for (Map<String, Object> row : rows) if (row.get("titleId") instanceof Integer id) track(id);
                    return rows;
                });
                page(ctx, owned);
            });
            routes.get("/online", VIEW, ctx -> page(ctx, ctx.sync(() ->
                Bukkit.getOnlinePlayers().stream().map(p -> { Map<String, Object> row = new LinkedHashMap<>(); row.put("uuid", p.getUniqueId().toString()); row.put("name", p.getName()); return row; }).toList())));
            routes.post("/create", MANAGE, ctx -> {
                Map<String, Object> body = ctx.bodyMap();
                String name = checked(() -> Inputs.text(body, "name", 256, false));
                String description = checked(() -> Inputs.text(body, "description", 2048, true));
                int days = checked(() -> Inputs.integer(body, "days", 0, 36500));
                boolean prefix = checked(() -> Inputs.bool(body, "prefix"));
                String mode = checked(() -> Inputs.choice(body, "mode", Set.of("NOT", "ACTIVITY")));
                Map<String, Object> created = ctx.sync(() -> {
                    requireApi(); int id = titleApi.create(name, description, days, prefix, mode);
                    track(id); Map<String, Object> after = titleApi.find(id).orElseThrow(() -> new IllegalStateException("创建API返回ID但即时查询不到"));
                    audit(ctx, "create", "id=" + id + "; mode=" + mode + "; after=" + after); return Map.of("id", id, "title", after);
                });
                ctx.json(Map.of("ok", true, "message", "已创建称号并读取确认", "result", created));
            });
            routes.post("/titles/{id}/rename", MANAGE, ctx -> {
                int id = pathId(ctx); Map<String, Object> body = ctx.bodyMap();
                String name = checked(() -> Inputs.text(body, "name", 256, false)); boolean prefix = checked(() -> Inputs.bool(body, "prefix"));
                ctx.ok(ctx.sync(() -> { requireApi(); var before = existing(id); titleApi.rename(id, name, prefix); var after = titleApi.find(id);
                    audit(ctx, "rename", "id=" + id + "; before=" + before + "; after=" + after); return "名称 API 已成功，已读取最新状态；请刷新目录核验"; }));
            });
            routes.post("/titles/{id}/description", MANAGE, ctx -> {
                int id = pathId(ctx); String description = checked(() -> Inputs.text(ctx.bodyMap(), "description", 2048, true));
                ctx.ok(ctx.sync(() -> { requireApi(); var before = existing(id); titleApi.describe(id, description); var after = titleApi.find(id);
                    audit(ctx, "description", "id=" + id + "; before=" + before + "; after=" + after); return "描述 API 已成功，已读取最新状态；请刷新目录核验"; }));
            });
            routes.post("/grant", MANAGE, ctx -> {
                Map<String, Object> body = ctx.bodyMap(); String input = checked(() -> Inputs.text(body, "player", 64, false));
                int id = checked(() -> Inputs.integer(body, "titleId", 1, Integer.MAX_VALUE)); int days = checked(() -> Inputs.integer(body, "days", 0, 36500));
                ctx.ok(ctx.sync(() -> { requireApi(); UUID uuid = resolve(input); existing(id); var before = titleApi.owned(uuid); titleApi.grant(uuid, id, days); var after = titleApi.owned(uuid); track(id);
                    audit(ctx, "grant", "player=" + uuid + "; title=" + id + "; days=" + days + "; before=" + before + "; after=" + after); return "授予 API 已成功，请搜索玩家核验最新持有状态"; }));
            });
            routes.post("/players/{uuid}/{titleId}/revoke", MANAGE, ctx -> {
                UUID uuid = checked(() -> Inputs.uuid(ctx.pathParam("uuid"))); int titleId = checked(() -> strictId(ctx.pathParam("titleId")));
                ctx.ok(ctx.sync(() -> { requireApi(); var before = titleApi.owned(uuid); if (before.stream().noneMatch(row -> Objects.equals(row.get("titleId"), titleId))) throw HttpException.badRequest("玩家未持有该称号");
                    titleApi.revoke(uuid, titleId); var after = titleApi.owned(uuid); audit(ctx, "revoke", "player=" + uuid + "; title=" + titleId + "; before=" + before + "; after=" + after); return "撤销 API 已成功，已读取最新状态；请刷新核验"; }));
            });
            routes.post("/players/{uuid}/{ownedId}/equip", MANAGE, ctx -> {
                UUID uuid = checked(() -> Inputs.uuid(ctx.pathParam("uuid"))); int ownedId = checked(() -> strictId(ctx.pathParam("ownedId")));
                ctx.ok(ctx.sync(() -> { requireApi(); if (Bukkit.getPlayer(uuid) == null) throw HttpException.badRequest("佩戴需玩家在线"); var before = titleApi.owned(uuid); titleApi.equip(uuid, ownedId); var after = titleApi.owned(uuid);
                    audit(ctx, "equip", "player=" + uuid + "; ownedId=" + ownedId + "; before=" + before + "; after=" + after); return "已调用佩戴 API，已读取最新状态；请刷新核验"; }));
            });
        });
        web.registerStatic(NS, getClassLoader(), "web", this);
        web.registerPanel(NS, panel(), this);
        registered = true;
        if (currentTitles != null) currentTitles.close();
        currentTitles = new CurrentTitleCache(this, titleApi); currentTitles.start();
    }
    static WebPanel panel() {
        WebPanel.Table catalog = new WebPanel.Table("商店公开称号 + 已验证追踪 ID（非完整目录）", "catalog").rowKey("id")
            .column("id", "定义 ID").column("titleName", "称号原始文本").column("description", "描述").column("day", "默认有效天数").column("isHide", "隐藏", "bool").column("source", "来源")
            .toolbarAction(new WebPanel.Action("lookup", "查找 ID 并加入目录", "lookup").permission(VIEW).field(field("id", "准确称号 ID", "number")))
            .toolbarAction(new WebPanel.Action("create", "创建称号", "create").permission(MANAGE)
                .field(field("name", "称号名称（支持 PlayerTitle 颜色码）", "text"))
                .field(new WebPanel.Field("description", "描述", "textarea"))
                .field(field("days", "默认天数，0 永久", "number").defaultValue(0))
                .field(new WebPanel.Field("prefix", "使用 PlayerTitle 默认前后缀", "bool").defaultValue(false))
                .field(new WebPanel.Field("mode", "获取方式", "select").option("NOT", "无条件").option("ACTIVITY", "活动或管理员授予").required(true)))
            .toolbarAction(new WebPanel.Action("grant", "授予玩家", "grant").permission(MANAGE)
                .field(field("player", "已知玩家名或完整 UUID", "text")).field(field("titleId", "定义 ID", "number"))
                .field(field("days", "授予天数，0 永久", "number").defaultValue(0)))
            .rowAction(new WebPanel.Action("rename", "改名", "titles/{id}/rename").permission(MANAGE)
                .field(field("name", "新称号名称", "text")).field(new WebPanel.Field("prefix", "使用默认前后缀", "bool").defaultValue(false)))
            .rowAction(new WebPanel.Action("description", "改描述", "titles/{id}/description").permission(MANAGE).field(new WebPanel.Field("description", "新描述", "textarea")))
            .rowAction(new WebPanel.Action("delete", "删除定义", "titles/{id}/delete").permission(MANAGE).style("danger").confirm("删除此 PlayerTitle 称号定义？该操作可能影响玩家持有记录，请确认已备份。"));
        return WebPanel.of("playertitle", "PlayerTitle 称号管理").icon("award").group("玩家").order(36).permission(VIEW)
            .section(new WebPanel.Text("目录范围与查询", "创建称号时可选择无条件获取或由管理员发放。目录展示商店称号及已查验的称号；其他称号请按准确编号查询。查询玩家持有记录时，请在搜索框输入完整 UUID 或已知玩家名称。佩戴操作使用持有记录编号，撤销操作使用称号编号。"))
            .section(catalog)
            .section(new WebPanel.Iframe("称号颜色预览（本地，不提交）", "/ext/playertitle/preview.html").height(240))
            .section(new WebPanel.Table("在线玩家（复制 UUID 到下方搜索框）", "online").rowKey("uuid").column("uuid", "UUID").column("name", "名字"))
            .section(new WebPanel.Table("玩家实际持有记录：在搜索框输入完整 UUID 或名字", "players").rowKey("id")
                .column("id", "持有记录 ID").column("titleId", "定义 ID").column("titleName", "称号").column("playerName", "玩家")
                .column("playerUuid", "UUID").column("expirationTime", "到期", "datetime").column("isUseShow", "正佩戴", "bool")
                .rowAction(new WebPanel.Action("equip", "佩戴", "players/{playerUuid}/{id}/equip").permission(MANAGE))
                .rowAction(new WebPanel.Action("revoke", "撤销", "players/{playerUuid}/{titleId}/revoke").permission(MANAGE).style("danger").confirm("撤销该玩家的此称号？")));
    }
    private static WebPanel.Field field(String key, String label, String type) { return new WebPanel.Field(key, label, type).required(true); }
    private Map<String, Object> existing(int id) throws Exception { var value = titleApi.find(id).orElseThrow(() -> HttpException.badRequest("称号不存在")); track(id); return value; }
    private void requireApi() { if (!registered || titleApi == null) throw HttpException.badRequest("PlayerTitle 不可用，请联系管理员"); }
    private static void page(WebContext ctx, List<Map<String, Object>> original) {
        String query = Optional.ofNullable(ctx.query("q")).orElse("").toLowerCase(Locale.ROOT);
        // The players endpoint uses q as the exact identity, not as a text filter.
        List<Map<String, Object>> rows = ctx.path().endsWith("/players") ? original : original.stream().filter(row -> row.toString().toLowerCase(Locale.ROOT).contains(query)).toList();
        int size = Math.min(100, Math.max(1, ctx.queryInt("size", 20))), page = Math.max(1, ctx.queryInt("page", 1));
        ctx.page(rows.size(), rows.stream().skip((long) (page - 1) * size).limit(size).toList());
    }
    private UUID resolve(String input) {
        try { return Inputs.uuid(input); } catch (IllegalArgumentException ignored) { }
        return LBR.get().players().uuidOf(input).orElseThrow(() -> HttpException.badRequest("未知玩家；使用已知名字或完整 UUID"));
    }
    private static int strictId(String input) { try { int id = Integer.parseInt(input); if (id < 1) throw new NumberFormatException(); return id; } catch (NumberFormatException ex) { throw new IllegalArgumentException("ID 必须为正整数"); } }
    private static int pathId(WebContext ctx) { return checked(() -> strictId(ctx.pathParam("id"))); }
    private static <T> T checked(java.util.function.Supplier<T> work) { try { return work.get(); } catch (IllegalArgumentException ex) { throw HttpException.badRequest(ex.getMessage()); } }
    private void audit(WebContext ctx, String action, String detail) {
        if (currentTitles != null && Set.of("equip", "grant", "revoke", "delete", "rename").contains(action)) currentTitles.refreshAll();
        LBR.get().audit().log(NS, "web:" + ctx.user().username(), action, detail);
    }
    @EventHandler public void pluginEnabled(PluginEnableEvent event) { if (event.getPlugin().getName().equals("PlayerTitle")) register(); }
    @EventHandler public void pluginDisabled(PluginDisableEvent event) { if (event.getPlugin().getName().equals("PlayerTitle")) unregister(); }
    private void unregister() { if (currentTitles != null) { currentTitles.close(); currentTitles = null; } registered = false; titleApi = null; if (LBR.isLoaded()) LBR.get().web().unregisterAll(this); }
    @Override public void onDisable() { unregister(); }
}
