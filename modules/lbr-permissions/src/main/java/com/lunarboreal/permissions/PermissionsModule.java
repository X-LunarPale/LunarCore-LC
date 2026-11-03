package com.lunarboreal.permissions;

import com.lunarboreal.api.module.LbrModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.permissions.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Uses public Bukkit attachments only; never changes operator status or other providers' attachments. */
public final class PermissionsModule extends LbrModule implements Listener {
    private PermissionStore store;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Map<UUID, Map<String,Boolean>> applied = new HashMap<>();
    private final Map<UUID, Map<String,Set<String>>> appliedGraphs = new HashMap<>();
    private boolean closing;
    private long lastWarning;
    public PermissionStore store() { return store; }
    public PermissionState state() { return store.snapshot(); }
    @Override public void onEnable() {
        if (config().getBoolean("refuse-other-providers", true)) {
            for (String name : List.of("LuckPerms", "PermissionsEx", "GroupManager", "UltraPermissions")) {
                if (Bukkit.getPluginManager().isPluginEnabled(name)) throw new IllegalStateException("检测到其他权限管理插件 " + name + "，请仅保留一个权限提供者");
            }
        }
        store = new PermissionStore(dataFolder().toPath().resolve("permissions.json"));
        try { store.load(); } catch (java.io.IOException ex) { throw new IllegalStateException("权限数据读取失败，拒绝覆盖原文件", ex); }
        registerPermission("lbr.permissions.admin", PermissionDefault.OP, "管理玩家与用户组权限");
        registerListener(this);
        registerCommand(new PermissionCommands(this));
        PermissionWeb.register(this);
        for (Player player : Bukkit.getOnlinePlayers()) remember(player);
        refreshAll();
        runTaskTimer(this::refreshAll, 20, Math.clamp(config().getInt("refresh-ticks", 20), 10, 1200));
    }
    @Override public void onDisable() {
        closing = true;
        for (Player player : Bukkit.getOnlinePlayers()) detach(player);
        attachments.clear(); applied.clear();
    }
    @Override public void onReload() { reloadPermissions(); }
    public void reloadPermissions() {
        if (closing) throw new IllegalStateException("模块正在关闭");
        try { store.load(); } catch (java.io.IOException ex) { throw new IllegalArgumentException("权限文件读取失败，保留当前权限", ex); }
        refreshAll();
    }
    public void mutate(String actor, String action, UnaryOperator<PermissionState> operation) throws Exception {
        if (closing) throw new IllegalStateException("模块正在关闭");
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("权限修改须在服务器主线程执行");
        store.update(operation);
        audit(actor, "permissions", action);
        refreshAll();
    }
    public void change(UnaryOperator<PermissionState> op, String actor, String detail) throws Exception { mutate(actor, detail, op); }
    public UUID identify(String token) { return resolveUser(token); }
    public UUID resolveUser(String token) {
        try { return UUID.fromString(token); } catch (IllegalArgumentException ignored) { }
        Player online = Bukkit.getPlayerExact(token);
        if (online != null) return online.getUniqueId();
        List<UUID> matches = state().users().values().stream().filter(user -> user.name().equalsIgnoreCase(token)).map(PermissionState.User::uuid).toList();
        if (matches.size() == 1) return matches.getFirst();
        throw new IllegalArgumentException(matches.isEmpty() ? "未知玩家，请使用UUID或让玩家先加入服务器" : "同名记录不唯一，请使用UUID");
    }
    public List<String> knownNodes() { return registeredNodes(); }
    public List<String> registeredNodes() {
        Set<String> nodes = new TreeSet<>();
        for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
            nodes.add(permission.getName().toLowerCase(Locale.ROOT));
            permission.getChildren().keySet().forEach(node -> nodes.add(node.toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(nodes);
    }
    public Map<String,Object> check(UUID id, String node) {
        node = PermissionState.node(node.toLowerCase(Locale.ROOT));
        var resolved = PermissionResolver.resolve(state(), id, node, System.currentTimeMillis());
        Player player = Bukkit.getPlayer(id);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("uuid", id.toString()); result.put("node", node);
        result.put("configured", resolved.isPresent()); result.put("value", resolved.orElse(false));
        result.put("online", player != null); result.put("bukkit", player != null && player.hasPermission(node));
        result.put("operator", player != null && player.isOp());
        return result;
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent event) { remember(event.getPlayer()); refresh(event.getPlayer(), candidates()); }
    @EventHandler public void quit(PlayerQuitEvent event) { detach(event.getPlayer()); }
    private void remember(Player player) {
        PermissionState.User old = state().users().get(player.getUniqueId());
        if (old != null && old.name().equals(player.getName())) return;
        try {
            store.update(snapshot -> {
                Map<UUID,PermissionState.User> users = new HashMap<>(snapshot.users());
                PermissionState.User previous = users.get(player.getUniqueId());
                users.put(player.getUniqueId(), new PermissionState.User(player.getUniqueId(), player.getName(), previous == null ? Map.of() : previous.groups(), previous == null ? Map.of() : previous.permissions()));
                return new PermissionState(snapshot.groups(), users, snapshot.defaultGroup());
            });
        } catch (Exception ex) { logger().warning("玩家权限档案保存失败：" + ex.getClass().getSimpleName()); }
    }
    private Set<String> candidates() {
        Set<String> nodes = new TreeSet<>(registeredNodes());
        state().groups().values().forEach(group -> nodes.addAll(group.permissions().keySet()));
        state().users().values().forEach(user -> nodes.addAll(user.permissions().keySet()));
        return nodes;
    }
    public void refreshAll() {
        if (closing) return;
        Set<String> nodes = candidates();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try { refresh(player, nodes); }
            catch (RuntimeException ex) {
                if (System.currentTimeMillis() - lastWarning > 60000) { lastWarning = System.currentTimeMillis(); logger().warning("权限应用失败：" + ex.getClass().getSimpleName()); }
            }
        }
    }
    private void refresh(Player player, Set<String> nodes) {
        Map<String,Boolean> next = new TreeMap<>();
        long now = System.currentTimeMillis();
        PermissionState snapshot = state();
        for (String node : nodes) {
            try { PermissionResolver.resolve(snapshot, player.getUniqueId(), node, now).ifPresent(value -> next.put(node, value)); }
            catch (IllegalArgumentException ignored) { /* Foreign plugins may register nodes outside our supported syntax. */ }
        }
        Map<String,Set<String>> children = new HashMap<>();
        for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
            Set<String> names = new HashSet<>(); permission.getChildren().keySet().forEach(name -> names.add(name.toLowerCase(Locale.ROOT)));
            children.put(permission.getName().toLowerCase(Locale.ROOT), names);
        }
        if (next.equals(applied.get(player.getUniqueId())) && children.equals(appliedGraphs.get(player.getUniqueId()))) return;
        List<String> order = AttachmentOrder.sort(next.keySet(), children);
        PermissionAttachment attachment = attachments.computeIfAbsent(player.getUniqueId(), ignored -> player.addAttachment(plugin()));
        // Clear before reinserting: updating an existing LinkedHashMap entry does not change child-expansion order.
        for (String node : List.copyOf(attachment.getPermissions().keySet())) attachment.unsetPermission(node);
        for (String node : order) attachment.setPermission(node, next.get(node));
        player.recalculatePermissions(); player.updateCommands(); applied.put(player.getUniqueId(), Map.copyOf(next)); appliedGraphs.put(player.getUniqueId(), Map.copyOf(children));
    }
    private void detach(Player player) {
        PermissionAttachment attachment = attachments.remove(player.getUniqueId()); applied.remove(player.getUniqueId()); appliedGraphs.remove(player.getUniqueId());
        if (attachment != null) { player.removeAttachment(attachment); player.updateCommands(); }
    }
}
