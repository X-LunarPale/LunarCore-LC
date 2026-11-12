package com.lunarboreal.core.command;

import com.lunarboreal.api.command.CommandRegistry;
import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.core.LunarCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * 把 {@link LcCommand} 注册为 Bukkit 命令，使用 lc 命名空间。
 * override=true 时移除同名原版/其他插件命令（含 minecraft: 前缀），注销时恢复。
 */
public final class LcCommandRegistry implements CommandRegistry {

    public static final String NAMESPACE = "lc";

    private final LunarCorePlugin plugin;
    /** 当前已注册的命令：命令名 → 注册信息 */
    private final Map<String, Registration> registered = new LinkedHashMap<>();
    /** 因 commands.disabled 跳过的命令，配置变化后可重新注册。 */
    private final List<Registration> skipped = new ArrayList<>();
    /** 同名命令覆盖栈：被后注册者替换的注册，后者注销时恢复。 */
    private final Map<String, java.util.Deque<Registration>> shadowed = new LinkedHashMap<>();
    private Set<String> disabled = new HashSet<>();
    private boolean serverLoaded = false;
    private boolean syncScheduled = false;

    private final class Registration {
        final Object owner;
        final LcCommand command;
        final Wrapper wrapper;
        /** 被 override 移除的原命令：label → Command */
        final Map<String, Command> displaced = new LinkedHashMap<>();

        Registration(Object owner, LcCommand command) {
            this.owner = owner;
            this.command = command;
            this.wrapper = new Wrapper(plugin, command, () -> plugin.isEnabled() && ownerEnabled(owner));
        }
    }

    public LcCommandRegistry(LunarCorePlugin plugin) {
        this.plugin = plugin;
        refreshDisabled();
    }

    private void refreshDisabled() {
        Set<String> set = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("commands.disabled")) set.add(s.toLowerCase(Locale.ROOT).trim());
        this.disabled = set;
    }

    /** 重新读取 commands.disabled：注销新禁用的命令，注册解除禁用的命令。 */
    public synchronized void reloadDisabled() {
        refreshDisabled();
        for (Registration r : new ArrayList<>(registered.values())) {
            if (isDisabled(r.command.name())) {
                doUnregister(r);
                skipped.add(r);
            }
        }
        for (Registration r : new ArrayList<>(skipped)) {
            if (!isDisabled(r.command.name())) {
                skipped.remove(r);
                register0(r.owner, r.command);
            }
        }
        scheduleSync();
    }

    // ------------------------------------------------------------------ CommandMap 访问

    public static CommandMap commandMap() {
        try {
            return Bukkit.getCommandMap(); // Paper 公开 API
        } catch (Throwable ignored) {
            // Spigot：回退反射 CraftServer#getCommandMap
        }
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
            return (CommandMap) m.invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法获取 CommandMap", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Command> knownCommands(CommandMap map) {
        try {
            return map.getKnownCommands(); // Paper 公开 API
        } catch (Throwable ignored) {
            // Spigot：回退反射
        }
        try {
            Method m = map.getClass().getMethod("getKnownCommands");
            return (Map<String, Command>) m.invoke(map);
        } catch (ReflectiveOperationException ignored) {
        }
        Class<?> c = map.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("knownCommands");
                f.setAccessible(true);
                return (Map<String, Command>) f.get(map);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                break;
            }
        }
        throw new IllegalStateException("无法访问 knownCommands");
    }

    // ------------------------------------------------------------------ 注册

    @Override
    public void register(LcModule owner, LcCommand command) {
        registerAny(owner, command);
    }

    @Override
    public void register(Plugin owner, LcCommand command) {
        registerAny(owner, command);
    }

    private synchronized void registerAny(Object owner, LcCommand command) {
        if (isDisabled(command.name())) {
            plugin.debug("命令 /" + command.name() + " 在 commands.disabled 中，跳过注册");
            skipped.removeIf(r -> r.command.name().equals(command.name()));
            skipped.add(new Registration(owner, command));
            return;
        }
        register0(owner, command);
        scheduleSync();
    }

    private void register0(Object owner, LcCommand command) {
        ensurePermission(Bukkit.getPluginManager(), command);
        Registration existing = registered.get(command.name());
        if (existing != null) {
            doUnregister(existing);
            if (existing.owner != owner) {
                plugin.getLogger().warning("命令 /" + command.name() + " 已由 " + LunarCorePlugin.ownerName(existing.owner)
                        + " 注册，将被 " + LunarCorePlugin.ownerName(owner) + " 覆盖（后者注销时恢复）");
                shadowed.computeIfAbsent(command.name(), k -> new java.util.ArrayDeque<>()).push(existing);
            }
        }
        Registration reg = new Registration(owner, command);
        CommandMap map = commandMap();
        Map<String, Command> known = knownCommands(map);
        if (command.isOverride()) displace(reg, known);
        // SimpleCommandMap also overwrites fallback-prefixed labels, even without override.
        for (String label : labels(command)) {
            String key = NAMESPACE + ":" + label;
            Command previous = known.get(key);
            if (previous != null) reg.displaced.putIfAbsent(key, previous);
        }
        map.register(NAMESPACE, reg.wrapper);
        reg.wrapper.active = true;
        if (command.isOverride()) {
            // 冲突时仍将无前缀标签指向当前命令。
            putSafe(known, command.name(), reg.wrapper);
            for (String a : command.aliases()) putSafe(known, a.toLowerCase(Locale.ROOT), reg.wrapper);
        }
        registered.put(command.name(), reg);
        plugin.debug("注册命令 /" + command.name() + (command.isOverride() ? "（抢占）" : "") + " ← " + LunarCorePlugin.ownerName(owner));
    }

    static void ensurePermission(PluginManager manager, LcCommand command) {
        ensurePermission(manager, command.permission(), command.description());
    }

    private static void ensurePermission(PluginManager manager, String node, String description) {
        if (node == null || node.isBlank() || manager.getPermission(node) != null) return;
        manager.addPermission(new Permission(node, description, PermissionDefault.OP));
    }

    private void displace(Registration reg, Map<String, Command> known) {
        for (String label : labels(reg.command)) {
            for (String key : List.of(label, "minecraft:" + label)) {
                Command c = known.get(key);
                if (c != null && !(c instanceof Wrapper)) {
                    reg.displaced.putIfAbsent(key, c);
                    removeSafe(known, key);
                }
            }
        }
    }

    private static List<String> labels(LcCommand command) {
        List<String> labels = new ArrayList<>();
        labels.add(command.name());
        for (String alias : command.aliases()) labels.add(alias.toLowerCase(Locale.ROOT));
        return labels;
    }

    private static boolean ownerEnabled(Object owner) {
        if (owner instanceof LcModule module) return module.isEnabled();
        if (owner instanceof Plugin ownerPlugin) return ownerPlugin.isEnabled();
        return false;
    }

    /**
     * Paper 26.3's forwarding map keySet iterator returns Command#getName(), so aliases and
     * namespaced labels are lost. entrySet exposes the actual Brigadier root-node labels.
     * Snapshot before mutating: the entry stream is backed by the dispatcher itself.
     */
    static List<String> ownedLabels(Map<String, Command> known, Command command) {
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Command> entry : known.entrySet()) {
            if (entry.getValue() == command) labels.add(entry.getKey());
        }
        return labels;
    }

    static void restoreMappings(Map<String, Command> known, Command wrapper, Map<String, Command> displaced) {
        for (String key : ownedLabels(known, wrapper)) {
            if (known.get(key) == wrapper) known.remove(key);
        }
        // Use put, not putIfAbsent: Paper's forwarding map inherits HashMap fast paths
        // that do not operate on the Brigadier dispatcher. Preserve later registrations.
        for (Map.Entry<String, Command> entry : displaced.entrySet()) {
            Command previous = entry.getValue();
            if (previous instanceof Wrapper old && (!old.active || !old.ownerEnabled.getAsBoolean())) continue;
            if (!known.containsKey(entry.getKey())) known.put(entry.getKey(), previous);
        }
    }

    private void putSafe(Map<String, Command> known, String key, Command c) {
        try {
            known.put(key, c);
        } catch (RuntimeException e) {
            plugin.debug("写入 knownCommands 失败 " + key + ": " + e);
        }
    }

    private void removeSafe(Map<String, Command> known, String key) {
        try {
            known.remove(key);
        } catch (RuntimeException e) {
            plugin.debug("移除 knownCommands 失败 " + key + ": " + e);
        }
    }

    /** 服务器加载完成后（原版命令可能在我们之后注册）重新抢占 override 命令。 */
    public synchronized void reapplyOverrides() {
        CommandMap map = commandMap();
        Map<String, Command> known = knownCommands(map);
        for (Registration r : registered.values()) {
            if (!r.command.isOverride()) continue;
            displace(r, known);
            putSafe(known, r.command.name(), r.wrapper);
            for (String a : r.command.aliases()) putSafe(known, a.toLowerCase(Locale.ROOT), r.wrapper);
        }
    }

    // ------------------------------------------------------------------ 注销

    @Override
    public synchronized void unregister(LcCommand command) {
        Registration r = registered.get(command.name());
        for (java.util.Deque<Registration> d : shadowed.values()) d.removeIf(s -> s.command == command);
        if (r != null && r.command == command) {
            doUnregister(r);
            restoreShadowed(command.name());
            scheduleSync();
        }
        skipped.removeIf(s -> s.command == command);
    }

    public synchronized void unregisterAll(Object owner) {
        boolean changed = false;
        for (java.util.Deque<Registration> d : shadowed.values()) d.removeIf(s -> s.owner == owner);
        shadowed.values().removeIf(java.util.Deque::isEmpty);
        for (Registration r : new ArrayList<>(registered.values())) {
            if (r.owner == owner) {
                doUnregister(r);
                restoreShadowed(r.command.name());
                changed = true;
            }
        }
        skipped.removeIf(s -> s.owner == owner);
        if (changed) scheduleSync();
    }

    /** 恢复被覆盖的同名命令（如果有）。 */
    private void restoreShadowed(String name) {
        java.util.Deque<Registration> d = shadowed.get(name);
        if (d == null) return;
        Registration prev = d.poll();
        if (d.isEmpty()) shadowed.remove(name);
        if (prev == null || registered.containsKey(name)) return;
        if (isDisabled(name)) {
            skipped.add(prev);
            return;
        }
        plugin.getLogger().info("恢复命令 /" + name + " ← " + LunarCorePlugin.ownerName(prev.owner));
        register0(prev.owner, prev.command);
    }

    private void doUnregister(Registration r) {
        // Invalidate first, including callbacks retained by an already-parsed Brigadier command.
        r.wrapper.active = false;
        registered.remove(r.command.name(), r);
        try {
            CommandMap map = commandMap();
            Map<String, Command> known = knownCommands(map);
            restoreMappings(known, r.wrapper, r.displaced);
            r.wrapper.unregister(map);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "注销命令 /" + r.command.name() + " 失败", e);
        }
        plugin.debug("注销命令 /" + r.command.name());
    }

    public synchronized void unregisterEverything() {
        shadowed.clear();
        for (Registration r : new ArrayList<>(registered.values())) doUnregister(r);
        skipped.clear();
        scheduleSync();
    }

    @Override
    public boolean isDisabled(String name) {
        return name != null && disabled.contains(name.toLowerCase(Locale.ROOT));
    }

    public synchronized Collection<String> registeredNames() {
        return List.copyOf(registered.keySet());
    }

    public synchronized Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        registered.forEach((k, v) -> out.put(k, LunarCorePlugin.ownerName(v.owner)));
        return out;
    }

    // ------------------------------------------------------------------ 同步到客户端

    public void markServerLoaded() {
        if (serverLoaded) return;
        registerCatalogPermissions();
        serverLoaded = true;
        scheduleSync();
    }

    private void registerCatalogPermissions() {
        try (InputStream input = plugin.getResource("command-permissions.txt")) {
            if (input == null) {
                plugin.getLogger().warning("命令权限目录缺失");
                return;
            }
            PluginManager manager = Bukkit.getPluginManager();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String node = line.strip();
                    if (!node.isEmpty() && !node.startsWith("#")) ensurePermission(manager, node, "");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "命令权限目录加载失败", e);
        }
    }

    /** 合并到下一 tick：同步 Brigadier 命令树并刷新所有玩家的命令列表。 */
    public void scheduleSync() {
        if (!serverLoaded || !plugin.isEnabled()) return;
        synchronized (this) {
            if (syncScheduled) return;
            syncScheduled = true;
        }
        Bukkit.getScheduler().runTask(plugin, this::syncNow);
    }

    public void syncNow() {
        synchronized (this) {
            syncScheduled = false;
        }
        try {
            Method m = Bukkit.getServer().getClass().getMethod("syncCommands");
            m.invoke(Bukkit.getServer());
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.debug("syncCommands 失败: " + e);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                p.updateCommands();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 包装

    static final class Wrapper extends Command implements PluginIdentifiableCommand {
        private final LunarCorePlugin plugin;
        private final LcCommand command;
        private final java.util.function.BooleanSupplier ownerEnabled;
        volatile boolean active;

        Wrapper(LunarCorePlugin plugin, LcCommand command, java.util.function.BooleanSupplier ownerEnabled) {
            super(command.name(), command.description(), command.usage().isEmpty() ? "/" + command.name() : command.usage(),
                    new ArrayList<>(command.aliases()));
            this.plugin = plugin;
            this.command = command;
            this.ownerEnabled = ownerEnabled;
            if (command.permission() != null && !command.permission().isEmpty()) setPermission(command.permission());
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!active || !ownerEnabled.getAsBoolean()) return true;
            String perm = command.permission();
            if (perm != null && !perm.isEmpty() && !sender.hasPermission(perm)) {
                plugin.messages().send(sender, "core.no-permission", "permission", perm);
                return true;
            }
            if (command.isPlayerOnly() && !(sender instanceof Player)) {
                plugin.messages().send(sender, "core.player-only");
                return true;
            }
            try {
                command.execute(sender, label, args);
            } catch (Throwable t) {
                plugin.messages().send(sender, "core.command-error");
                plugin.getLogger().log(Level.SEVERE, "执行命令 /" + label + " 时出错", t);
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!active || !ownerEnabled.getAsBoolean()) return Collections.emptyList();
            String perm = command.permission();
            if (perm != null && !perm.isEmpty() && !sender.hasPermission(perm)) return Collections.emptyList();
            try {
                List<String> r = command.tabComplete(sender, alias, args);
                return r == null ? Collections.emptyList() : r;
            } catch (Throwable t) {
                plugin.debug("Tab 补全出错 /" + alias + ": " + t);
                return Collections.emptyList();
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) {
            return tabComplete(sender, alias, args);
        }
    }
}
