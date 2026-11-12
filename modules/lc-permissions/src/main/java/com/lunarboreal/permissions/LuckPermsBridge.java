package com.lunarboreal.permissions;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.node.types.WeightNode;
import net.luckperms.api.util.Tristate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

final class LuckPermsBridge implements PermissionBridge {
    private final LuckPerms lp;
    private volatile Set<UUID> userIds = Set.of();
    private volatile long usersRefreshed;

    private LuckPermsBridge(LuckPerms lp) { this.lp = lp; }

    static PermissionBridge connect() { return new LuckPermsBridge(LuckPermsProvider.get()); }

    @Override public String initialize(Path directory, boolean migrate) throws Exception {
        await(lp.getGroupManager().loadAllGroups());
        if (!migrate) return "旧数据导入已关闭";
        Path marker = directory.resolve("luckperms-imported.txt");
        if (Files.exists(marker)) return "旧权限已导入";
        Path oldFile = directory.resolve("permissions.json");
        PermissionState legacy = Files.exists(oldFile) ? LegacyPermissions.read(oldFile) : null;
        long now = System.currentTimeMillis();
        Set<UUID> ids = new HashSet<>(await(lp.getUserManager().getUniqueUsers()));

        if (legacy != null) {
            for (var old : legacy.groups().values()) await(lp.getGroupManager().createAndLoadGroup(old.name()));
            for (var old : legacy.groups().values()) {
                Group group = requireGroup(old.name());
                if (group.getNodes(NodeType.WEIGHT).stream().noneMatch(LuckPermsBridge::global))
                    group.data().add(WeightNode.builder(old.weight()).build());
                old.parents().forEach(parent -> addMissing(group, InheritanceNode.builder(parent).build()));
                LegacyPermissions.grants(old.permissions(), now).forEach(entry -> addMissing(group, imported(entry)));
                await(lp.getGroupManager().saveGroup(group));
            }
            if (!legacy.defaultGroup().equals("default")) {
                Group defaults = await(lp.getGroupManager().createAndLoadGroup("default"));
                addMissing(defaults, InheritanceNode.builder(legacy.defaultGroup()).build());
                await(lp.getGroupManager().saveGroup(defaults));
            }
            for (var old : legacy.users().values()) {
                withUser(old.uuid(), old.name(), user -> {
                    LegacyPermissions.memberships(old.groups(), now).forEach(entry -> addMissing(user, imported(entry)));
                    LegacyPermissions.grants(old.permissions(), now).forEach(entry -> addMissing(user, imported(entry)));
                    await(lp.getUserManager().saveUser(user));
                    return null;
                });
                ids.add(old.uuid());
            }
        }
        for (Group group : lp.getGroupManager().getLoadedGroups()) {
            if (renameLegacyNodes(group)) await(lp.getGroupManager().saveGroup(group));
        }
        for (UUID id : ids) {
            withUser(id, null, user -> {
                if (renameLegacyNodes(user)) await(lp.getUserManager().saveUser(user));
                return null;
            });
        }
        Files.createDirectories(directory);
        Files.writeString(marker, "LuckPerms import completed " + Instant.now() + System.lineSeparator(), StandardCharsets.UTF_8);
        return legacy == null ? "无需导入旧权限" : "已导入 " + legacy.groups().size() + " 个权限组、" + legacy.users().size() + " 位玩家";
    }

    @Override public List<Map<String, Object>> groups() {
        return lp.getGroupManager().getLoadedGroups().stream().sorted(Comparator.comparing(Group::getName))
                .map(group -> Map.<String, Object>of("name", group.getName(), "weight", group.getWeight().orElse(0),
                        "parents", describe(group, true), "permissions", describe(group, false))).toList();
    }

    @Override public Page users(String query, int page, int size) {
        if (System.currentTimeMillis() - usersRefreshed > 10000) {
            userIds = Set.copyOf(await(lp.getUserManager().getUniqueUsers()));
            usersRefreshed = System.currentTimeMillis();
        }
        Set<UUID> candidates = new TreeSet<>(userIds);
        lp.getUserManager().getLoadedUsers().forEach(user -> candidates.add(user.getUniqueId()));
        String search = query.strip().toLowerCase(Locale.ROOT);
        if (!search.isEmpty()) {
            Set<UUID> found = new HashSet<>();
            candidates.stream().filter(id -> id.toString().contains(search)).forEach(found::add);
            lp.getUserManager().getLoadedUsers().stream()
                    .filter(user -> Objects.toString(user.getUsername(), "").toLowerCase(Locale.ROOT).contains(search))
                    .forEach(user -> found.add(user.getUniqueId()));
            if (search.matches("[a-z0-9_]{1,16}")) {
                UUID exact = await(lp.getUserManager().lookupUniqueId(search));
                if (exact != null) found.add(exact);
            }
            candidates.retainAll(found);
        }
        List<UUID> sorted = List.copyOf(candidates);
        int start = (int) Math.min((long) (page - 1) * size, sorted.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UUID id : sorted.subList(start, Math.min(start + size, sorted.size()))) {
            rows.add(withUser(id, null, user -> Map.of("uuid", id.toString(),
                    "name", Objects.toString(user.getUsername(), ""), "groups", describe(user, true),
                    "primaryGroup", user.getPrimaryGroup(), "permissions", describe(user, false))));
        }
        return new Page(sorted.size(), rows);
    }

    @Override public UUID identify(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { }
        User loaded = lp.getUserManager().getUser(value);
        if (loaded != null) return loaded.getUniqueId();
        if (!value.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("请填写玩家 UUID 或完整名称");
        UUID id = await(lp.getUserManager().lookupUniqueId(value));
        if (id == null) throw new IllegalArgumentException("未找到玩家，请使用 UUID 或让玩家先加入服务器");
        return id;
    }

    @Override public List<Map<String, Object>> effective(UUID id) {
        return withUser(id, null, user -> user.getCachedData().getPermissionData(user.getQueryOptions())
                .getPermissionMap().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of("uuid", id.toString(), "node", entry.getKey(), "value", entry.getValue()))
                .toList());
    }

    @Override public Map<String, Object> check(UUID id, String node) {
        String key = permission(node);
        return withUser(id, null, user -> {
            Tristate result = user.getCachedData().getPermissionData(user.getQueryOptions()).checkPermission(key);
            var out = new LinkedHashMap<String, Object>();
            out.put("uuid", id.toString());
            out.put("node", key);
            out.put("configured", result != Tristate.UNDEFINED);
            out.put("value", result.asBoolean());
            out.put("result", result == Tristate.UNDEFINED ? "未设置" : result.asBoolean() ? "允许" : "拒绝");
            out.put("primaryGroup", user.getPrimaryGroup());
            return out;
        });
    }

    @Override public synchronized void mutate(String scope, String target, String action, String key, String value, String duration) {
        if (scope.equals("group")) {
            String name = groupName(target);
            if (action.equals("create")) {
                if (await(lp.getGroupManager().loadGroup(name)).isPresent()) throw new IllegalArgumentException("权限组已存在");
                await(lp.getGroupManager().createAndLoadGroup(name));
                return;
            }
            Group group = requireGroup(name);
            if (action.equals("delete")) {
                if (name.equals("default")) throw new IllegalArgumentException("不能删除默认组");
                if (lp.getGroupManager().getLoadedGroups().stream().anyMatch(other -> inherits(other, name))
                        || !await(lp.getUserManager().getWithPermission("group." + name)).isEmpty())
                    throw new IllegalArgumentException("此权限组仍被其他组或玩家引用，请先移除继承");
                await(lp.getGroupManager().deleteGroup(group));
                return;
            }
            change(group, action, key, value, duration);
            await(lp.getGroupManager().saveGroup(group));
        } else if (scope.equals("user")) {
            UUID id = identify(target);
            withUser(id, null, user -> {
                change(user, action, key, value, duration);
                await(lp.getUserManager().saveUser(user));
                return null;
            });
            usersRefreshed = 0;
        } else {
            throw new IllegalArgumentException("不支持的操作对象");
        }
    }

    private void change(PermissionHolder holder, String action, String key, String value, String duration) {
        if (action.equals("weight")) {
            if (!(holder instanceof Group)) throw new IllegalArgumentException("仅权限组支持权重");
            int weight;
            try { weight = Integer.parseInt(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException("权重须为整数"); }
            holder.data().clear(node -> global(node) && node instanceof WeightNode);
            holder.data().add(WeightNode.builder(weight).build());
            return;
        }
        if (action.equals("parent-add") || action.equals("parent-remove")) {
            String parent = groupName(key);
            if (action.equals("parent-add")) requireGroup(parent);
            if (action.equals("parent-add") && holder instanceof Group group) preventCycle(group.getName(), parent);
            long expires = action.equals("parent-add") ? expiry(duration) : 0;
            holder.data().clear(node -> global(node) && node instanceof InheritanceNode inheritance && inheritance.getGroupName().equals(parent));
            if (action.equals("parent-add")) {
                var builder = InheritanceNode.builder(parent);
                if (expires != 0) builder.expiry(Instant.ofEpochMilli(expires));
                holder.data().add(builder.build());
            }
            return;
        }
        if (action.equals("permission-set") || action.equals("permission-unset")) {
            String nodeKey = permission(key);
            boolean allowed = false;
            long expires = 0;
            if (action.equals("permission-set")) {
                if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("权限值须为允许或拒绝");
                allowed = Boolean.parseBoolean(value);
                expires = expiry(duration);
            }
            holder.data().clear(node -> global(node) && node instanceof PermissionNode permission && permission.getPermission().equals(nodeKey));
            if (action.equals("permission-set")) {
                var builder = PermissionNode.builder(nodeKey).value(allowed);
                if (expires != 0) builder.expiry(Instant.ofEpochMilli(expires));
                holder.data().add(builder.build());
            }
            return;
        }
        throw new IllegalArgumentException("不支持的权限操作");
    }

    private void preventCycle(String group, String parent) {
        var pending = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        pending.add(parent);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (name.equals(group)) throw new IllegalArgumentException("权限组不能循环继承");
            if (!visited.add(name)) continue;
            Group current = lp.getGroupManager().getGroup(name);
            if (current != null) current.getNodes(NodeType.INHERITANCE).stream()
                    .filter(node -> node.getValue() && !node.hasExpired()).forEach(node -> pending.add(node.getGroupName()));
        }
    }

    private Group requireGroup(String name) {
        Group group = lp.getGroupManager().getGroup(name);
        if (group != null) return group;
        return await(lp.getGroupManager().loadGroup(name)).orElseThrow(() -> new IllegalArgumentException("权限组不存在：" + name));
    }

    private <T> T withUser(UUID id, String name, Function<User, T> operation) {
        User existing = lp.getUserManager().getUser(id);
        User user = existing == null ? await(lp.getUserManager().loadUser(id, name)) : existing;
        try { return operation.apply(user); }
        finally { if (existing == null) lp.getUserManager().cleanupUser(user); }
    }

    private static Node imported(LegacyPermissions.Entry entry) {
        if (entry.inheritance()) {
            var builder = InheritanceNode.builder(entry.key()).value(entry.value());
            if (entry.expiresAt() != 0) builder.expiry(Instant.ofEpochMilli(entry.expiresAt()));
            return builder.build();
        }
        var builder = PermissionNode.builder(entry.key()).value(entry.value());
        if (entry.expiresAt() != 0) builder.expiry(Instant.ofEpochMilli(entry.expiresAt()));
        return builder.build();
    }

    private static void addMissing(PermissionHolder holder, Node node) {
        if (holder.data().toCollection().stream().noneMatch(current -> current.getKey().equals(node.getKey()) && current.getContexts().equals(node.getContexts())))
            holder.data().add(node);
    }

    private static boolean renameLegacyNodes(PermissionHolder holder) {
        boolean changed = false;
        for (Node node : List.copyOf(holder.data().toCollection())) {
            if (!(node instanceof PermissionNode permission)) continue;
            String current = LegacyPermissions.currentNode(permission.getPermission());
            if (current.equals(permission.getPermission())) continue;
            var builder = PermissionNode.builder(current).value(node.getValue()).withContext(node.getContexts());
            if (node.hasExpiry()) builder.expiry(node.getExpiry());
            addMissing(holder, builder.build());
            holder.data().remove(node);
            changed = true;
        }
        return changed;
    }

    private static boolean global(Node node) { return node.getContexts().isEmpty(); }

    private static boolean inherits(Group group, String parent) {
        return group.getNodes(NodeType.INHERITANCE).stream().anyMatch(node -> node.getGroupName().equals(parent));
    }

    private static String describe(PermissionHolder holder, boolean groups) {
        return holder.data().toCollection().stream().filter(node -> !node.hasExpired())
                .filter(node -> groups ? node instanceof InheritanceNode : node instanceof PermissionNode)
                .sorted(Comparator.comparing(Node::getKey)).map(node -> {
                    String key = node instanceof InheritanceNode inheritance ? inheritance.getGroupName() : node.getKey();
                    String text = key + (groups && node.getValue() ? "" : "=" + node.getValue());
                    if (node.hasExpiry()) text += "（到期：" + node.getExpiry() + "）";
                    if (!global(node)) text += " [" + node.getContexts().toFlattenedMap().entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(", ")) + "]";
                    return text;
                }).collect(Collectors.joining(", "));
    }

    static String permission(String value) {
        if (value == null || value.isBlank() || value.length() > 200 || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalArgumentException("权限节点无效");
        return value.toLowerCase(Locale.ROOT);
    }

    private static String groupName(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,100}")) throw new IllegalArgumentException("组名仅支持字母、数字、下划线与短横线");
        return value.toLowerCase(Locale.ROOT);
    }

    static long expiry(String duration) {
        if (duration == null || duration.isBlank() || duration.equals("0")) return 0;
        var match = java.util.regex.Pattern.compile("([1-9][0-9]{0,9})(s|min|m|h|d)?").matcher(duration.strip().toLowerCase(Locale.ROOT));
        if (!match.matches()) throw new IllegalArgumentException("有效期须为秒数或带 s/min/h/d 后缀，0 表示永久");
        long factor = switch (Objects.toString(match.group(2), "s")) {
            case "min", "m" -> 60000L;
            case "h" -> 3600000L;
            case "d" -> 86400000L;
            default -> 1000L;
        };
        try { return Math.addExact(System.currentTimeMillis(), Math.multiplyExact(Long.parseLong(match.group(1)), factor)); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("有效期超出范围"); }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try { return future.get(20, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("LuckPerms 操作已中断", ex); }
        catch (TimeoutException ex) { throw new IllegalStateException("LuckPerms 存储响应超时", ex); }
        catch (ExecutionException ex) { throw new IllegalStateException("LuckPerms 操作失败：" + Objects.toString(ex.getCause().getMessage(), ex.getCause().getClass().getSimpleName()), ex.getCause()); }
    }
}
