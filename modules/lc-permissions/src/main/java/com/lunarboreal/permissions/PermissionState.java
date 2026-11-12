package com.lunarboreal.permissions;

import java.util.*;

/** Legacy import format. Epoch milliseconds; zero means permanent. */
public record PermissionState(Map<String, Group> groups, Map<UUID, User> users, String defaultGroup) {
    public record Grant(boolean value, long expiresAt) {
        public Grant { if (expiresAt < 0) throw new IllegalArgumentException("过期时间不能为负数"); }
        public boolean active(long now) { return expiresAt == 0 || now < expiresAt; }
    }
    public record Membership(long expiresAt) {
        public Membership { if (expiresAt < 0) throw new IllegalArgumentException("过期时间不能为负数"); }
        public boolean active(long now) { return expiresAt == 0 || now < expiresAt; }
    }
    public record Group(String name, int weight, Set<String> parents, Map<String, Grant> permissions) {
        public Group {
            groupName(name); if (weight < -1000000 || weight > 1000000) throw new IllegalArgumentException("权重超出范围");
            parents = Set.copyOf(parents); parents.forEach(PermissionState::groupName);
            permissions = checkedGrants(permissions);
        }
    }
    public record User(UUID uuid, String name, Map<String, Membership> groups, Map<String, Grant> permissions) {
        public User {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null || name.length() > 64 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("玩家名称无效");
            groups = Map.copyOf(groups); groups.keySet().forEach(PermissionState::groupName);
            permissions = checkedGrants(permissions);
        }
    }
    public PermissionState {
        groups = Map.copyOf(groups); users = Map.copyOf(users); groupName(defaultGroup);
        if (groups.size() > 1000 || users.size() > 100000) throw new IllegalArgumentException("权限记录超出上限");
        if (!groups.containsKey(defaultGroup)) throw new IllegalArgumentException("默认组不存在");
        for (var entry : groups.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().name())) throw new IllegalArgumentException("组名与索引不一致");
            for (String parent : entry.getValue().parents()) if (!groups.containsKey(parent)) throw new IllegalArgumentException("父组不存在: " + parent);
        }
        for (var entry : users.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().uuid())) throw new IllegalArgumentException("玩家UUID与索引不一致");
            for (String group : entry.getValue().groups().keySet()) if (!groups.containsKey(group)) throw new IllegalArgumentException("玩家组不存在: " + group);
        }
        Set<String> complete = new HashSet<>();
        for (String group : groups.keySet()) visit(group, groups, new HashSet<>(), complete, 0);
    }
    public static PermissionState initial() { return new PermissionState(Map.of("default", new Group("default", 0, Set.of(), Map.of())), Map.of(), "default"); }
    public static void groupName(String value) { if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,47}")) throw new IllegalArgumentException("组名须为1–48位小写字母数字或_-字符"); }
    public static String node(String value) {
        if (value == null || value.length() > 200 || !value.matches("(?:\\*|[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*(?:\\.\\*)?)")) throw new IllegalArgumentException("权限节点无效（仅允许末尾通配符）");
        return value;
    }
    private static Map<String, Grant> checkedGrants(Map<String, Grant> value) {
        if (value.size() > 10000) throw new IllegalArgumentException("单项权限过多");
        value.keySet().forEach(PermissionState::node); return Map.copyOf(value);
    }
    private static void visit(String group, Map<String, Group> groups, Set<String> path, Set<String> complete, int depth) {
        if (depth > 128) throw new IllegalArgumentException("继承深度超过128");
        if (complete.contains(group)) return;
        if (!path.add(group)) throw new IllegalArgumentException("组继承存在循环");
        for (String parent : groups.get(group).parents()) visit(parent, groups, path, complete, depth + 1);
        path.remove(group); complete.add(group);
    }
}
