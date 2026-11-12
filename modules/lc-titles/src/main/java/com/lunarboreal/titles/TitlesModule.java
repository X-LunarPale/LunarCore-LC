package com.lunarboreal.titles;

import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebPanel;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TitlesModule extends LcModule implements org.bukkit.event.Listener {
    private volatile Map<String, TitleDefinition> definitions = Map.of();
    private final Map<UUID, TitleDefinition> visible = new ConcurrentHashMap<>();
    private TitleStore store;
    private volatile boolean closing;

    @Override public void onEnable() {
        store = new TitleStore(dataFolder().toPath().resolve("players.tsv"));
        try { store.load(); } catch (IOException ex) { throw new IllegalStateException("Cannot safely load titles; refusing to overwrite player state", ex); }
        loadDefinitions();
        defaultPermissions("lc.titles.use");
        registerPermission("lc.titles.admin", PermissionDefault.OP, "授予、撤销及管理离线玩家称号");
        registerCommand(new TitlesCommand());
        registerListener(this);
        // Permission checks and Bukkit player access happen only on the main thread.
        runTaskTimer(this::refreshVisible, 1, 20);
        placeholder((player, key) -> {
            if (player == null) return "";
            TitleDefinition title = visible.get(player.getUniqueId());
            return switch (key) {
                case "title" -> title == null ? "" : title.safeMini();
                case "plain" -> title == null ? "" : title.plain();
                case "id" -> title == null ? "" : title.id();
                default -> null;
            };
        });
        registerWeb();
    }
    @Override public void onReload() { super.onReload(); loadDefinitions(); refreshVisible(); }
    @Override public synchronized void onDisable() { closing = true; visible.clear(); }
    @org.bukkit.event.EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) { visible.remove(event.getPlayer().getUniqueId()); }

    private void loadDefinitions() {
        Map<String, TitleDefinition> loaded = new LinkedHashMap<>();
        for (Map<?, ?> row : config().getMapList("titles")) {
            TitleDefinition title = new TitleDefinition(string(row, "id"), string(row, "display"), string(row, "permission"));
            if (loaded.putIfAbsent(title.id(), title) != null) throw new IllegalArgumentException("重复称号 ID: " + title.id());
        }
        definitions = Collections.unmodifiableMap(loaded);
    }
    private static String string(Map<?, ?> row, String key) {
        Object value = row.get(key);
        if (value == null && key.equals("permission")) return "";
        if (!(value instanceof String s)) throw new IllegalArgumentException(key + " 必须为文本");
        return s;
    }
    private void refreshVisible() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId(); online.add(uuid);
            TitleDefinition title = definitions.get(store.get(uuid).equipped());
            if (title != null && (title.permission().isBlank() || player.hasPermission(title.permission()))) visible.put(uuid, title);
            else visible.remove(uuid);
        }
        visible.keySet().retainAll(online);
    }
    private UUID resolve(String input) {
        try { return UUID.fromString(input); } catch (IllegalArgumentException ignored) { }
        return api().players().uuidOf(input).orElseThrow(() -> new IllegalArgumentException("未知玩家；请填写曾加入玩家名或完整 UUID"));
    }
    private void validate(String action, UUID target, String id, boolean admin) {
        if (!List.of("grant", "revoke", "equip", "clear").contains(action)) throw new IllegalArgumentException("未知操作");
        if ((action.equals("grant") || action.equals("revoke")) && !admin) throw new IllegalArgumentException("需要管理权限");
        if (action.equals("clear")) return;
        TitleDefinition title = definitions.get(TitleDefinition.validId(id));
        if (title == null && !action.equals("revoke")) throw new IllegalArgumentException("未知称号");
        if (action.equals("equip") && title != null && !title.permission().isEmpty()) {
            Player player = Bukkit.getPlayer(target);
            if (player == null || !player.hasPermission(title.permission())) throw new IllegalArgumentException("玩家需在线且拥有称号要求的权限才能佩戴");
        }
    }
    private synchronized void mutate(UUID target, String action, String title, String actor) throws IOException {
        if (closing) throw new IllegalStateException("称号模块正在关闭");
        store.change(target, action, title);
        visible.remove(target);
        audit(actor, action, target + " title=" + title);
    }
    private synchronized void reply(CommandSender sender, String text, boolean refresh) {
        if (closing) return;
        runTask(() -> {
            if (closing) return;
            if (refresh) refreshVisible();
            sender.sendMessage(api().messages().parse("<aqua>[<bold>称号</bold>]</aqua> <white>" + text));
        });
    }
    private void changeDefinition(String action, String id, TitleDefinition definition, String actor) throws IOException {
        if (closing) throw new IllegalStateException("称号模块正在关闭");
        Map<String, TitleDefinition> next = TitleDefinitions.save(dataFolder().toPath().resolve("config.yml"), definitions, action, definition, id);
        config().set("titles", next.values().stream().map(t -> Map.of("id", t.id(), "display", t.display(), "permission", t.permission())).toList());
        definitions = next;
        refreshVisible();
        audit(actor, "definition." + action, id);
    }
    private final class TitlesCommand extends LcCommand {
        TitlesCommand() { super("titles", "lc.titles.use", "title"); description("称号管理"); usage("/titles list|equip <id>|clear|grant|revoke <player/uuid> <id>|create|update <id> <文本...>|delete <id>"); }
        @Override public void execute(CommandSender sender, String label, String[] args) {
            try {
                String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
                boolean admin = sender.hasPermission("lc.titles.admin");
                UUID self = sender instanceof Player p ? p.getUniqueId() : null;
                if (List.of("create", "update", "delete").contains(action)) {
                    if (!admin) throw new IllegalArgumentException("需要 lc.titles.admin 管理权限");
                    if (action.equals("delete") ? args.length != 2 : args.length < 3)
                        throw new IllegalArgumentException("用法: /titles create|update <id> <单行称号文本...> 或 /titles delete <id>");
                    String id = TitleDefinition.validId(args[1]);
                    TitleDefinition previous = definitions.get(id);
                    TitleDefinition definition = action.equals("delete") ? null : new TitleDefinition(id,
                            String.join(" ", Arrays.copyOfRange(args, 2, args.length)), previous == null ? "" : previous.permission());
                    changeDefinition(action, id, definition, sender.getName());
                    sender.sendMessage(net.kyori.adventure.text.Component.text("称号定义已保存，仅供 TAB 显示；请再授予并佩戴"));
                    return;
                }
                if (action.equals("list")) {
                    if (args.length > 2 || args.length == 2 && !admin) throw new IllegalArgumentException("查看他人需要管理权限");
                    UUID target = args.length == 2 && admin ? resolve(args[1]) : self;
                    if (target == null) throw new IllegalArgumentException("控制台用 /titles list <player/uuid>");
                    TitleStore.State state = store.get(target);
                    sender.sendMessage(net.kyori.adventure.text.Component.text("已拥有: " + state.owned() + "；佩戴: " + state.equipped()));
                    for (TitleDefinition title : definitions.values()) sender.sendMessage(net.kyori.adventure.text.Component.text(title.id() + " ").append(title.component()));
                    return;
                }
                boolean other = action.equals("grant") || action.equals("revoke");
                if (args.length != (other ? 3 : action.equals("clear") ? 1 : 2)) throw new IllegalArgumentException("用法: /titles list|equip <id>|clear|grant|revoke <player/uuid> <id>");
                UUID target = other ? resolve(args[1]) : self;
                if (target == null) throw new IllegalArgumentException("该操作需要玩家身份");
                String title = action.equals("clear") ? "" : args[other ? 2 : 1];
                validate(action, target, title, admin);
                String actor = sender.getName();
                runAsync(() -> {
                    try { mutate(target, action, title, actor); reply(sender, "称号已更新", true); }
                    catch (Exception ex) { reply(sender, "称号更新失败: " + ex.getMessage(), false); }
                });
            } catch (IllegalArgumentException | IOException ex) { sender.sendMessage(net.kyori.adventure.text.Component.text("称号操作失败: " + ex.getMessage())); }
        }
        @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) return filter(sender.hasPermission("lc.titles.admin") ? List.of("list", "equip", "clear", "grant", "revoke", "create", "update", "delete") : List.of("list", "equip", "clear"), args[0]);
            if (args.length == 2 && (args[0].equals("equip") || sender.hasPermission("lc.titles.admin") && List.of("update", "delete").contains(args[0]))
                    || args.length == 3 && sender.hasPermission("lc.titles.admin") && List.of("grant", "revoke").contains(args[0]))
                return filter(new ArrayList<>(definitions.keySet()), args[args.length - 1]);
            return List.of();
        }
    }
    private void registerWeb() {
        webPermission("titles.view", "查看称号与玩家持有记录");
        webPermission("titles.manage", "授予、撤销及佩戴玩家称号");
        webRoutes(r -> r.get("/definitions", "titles.view", ctx -> ctx.page(definitions.size(), definitions.values().stream().map(t -> Map.of("id", t.id(), "display", t.display(), "permission", t.permission())).toList()))
            .post("/definitions", "titles.manage", ctx -> {
                Map<String, Object> body = ctx.bodyMap();
                try {
                    String action = string(body, "action"), id = TitleDefinition.validId(string(body, "id"));
                    if (!List.of("save", "delete").contains(action)) throw new IllegalArgumentException("未知定义操作");
                    TitleDefinition definition = action.equals("save") ? new TitleDefinition(id, string(body, "display"), string(body, "permission")) : null;
                    ctx.sync(() -> {
                        if (closing) throw new IllegalStateException("称号模块正在关闭");
                        changeDefinition(action, id, definition, "web:" + ctx.user().username());
                        return null;
                    });
                    ctx.ok("称号定义已更新");
                } catch (IllegalArgumentException ex) { throw HttpException.badRequest(ex.getMessage()); }
            })
            .get("/players", "titles.view", ctx -> {
                int page = Math.max(1, ctx.queryInt("page", 1)), size = Math.min(100, Math.max(1, ctx.queryInt("size", 20)));
                String query = Optional.ofNullable(ctx.query("q")).orElse("").toLowerCase(Locale.ROOT);
                List<Map<String, Object>> rows = store.snapshot().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>(); row.put("uuid", e.getKey().toString()); row.put("name", api().players().nameOf(e.getKey())); row.put("owned", String.join(", ", new TreeSet<>(e.getValue().owned()))); row.put("equipped", e.getValue().equipped()); return row;
                }).filter(row -> row.toString().toLowerCase(Locale.ROOT).contains(query)).toList();
                ctx.page(rows.size(), rows.stream().skip((long) (page - 1) * size).limit(size).toList());
            })
            .post("/change", "titles.manage", ctx -> {
                Map<String, Object> body = ctx.bodyMap();
                try {
                    String action = string(body, "action"), player = string(body, "player"), title = body.get("title") == null ? "" : string(body, "title");
                    if (player.length() > 64) throw new IllegalArgumentException("玩家标识过长");
                    UUID target = resolve(player);
                    ctx.sync(() -> { validate(action, target, title, true); return null; });
                    mutate(target, action, title, "web:" + ctx.user().username());
                    ctx.sync(() -> { if (!closing) refreshVisible(); return null; });
                    ctx.ok("称号已更新");
                } catch (IllegalArgumentException ex) { throw HttpException.badRequest(ex.getMessage()); }
            }));
        webPanel(WebPanel.of("titles", "称号管理").icon("award").group("玩家").order(35).permission("titles.view")
            .section(new WebPanel.Table("称号定义", "definitions").rowKey("id").column("id", "ID").column("display", "显示文本").column("permission", "佩戴权限")
                .toolbarAction(new WebPanel.Action("definition", "编辑称号定义", "definitions").permission("titles.manage")
                    .field(new WebPanel.Field("action", "操作", "select").option("save", "添加或更新").option("delete", "删除").required(true))
                    .field(new WebPanel.Field("id", "称号 ID", "text").required(true))
                    .field(new WebPanel.Field("display", "显示文本（仅颜色装饰）", "text"))
                    .field(new WebPanel.Field("permission", "额外佩戴权限（空为无需）", "text"))))
            .section(new WebPanel.Table("玩家称号", "players").rowKey("uuid").column("uuid", "玩家", "player").column("name", "名字").column("owned", "拥有称号").column("equipped", "佩戴")
                .toolbarAction(new WebPanel.Action("change", "管理玩家称号", "change").permission("titles.manage")
                    .field(new WebPanel.Field("player", "玩家名或 UUID（支持离线）", "text").required(true))
                    .field(new WebPanel.Field("action", "操作", "select").option("grant", "授予").option("revoke", "撤销").option("equip", "佩戴").option("clear", "取消佩戴").required(true))
                    .field(new WebPanel.Field("title", "称号 ID（取消佩戴留空）", "text")))));
    }
}
