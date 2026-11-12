package com.lunarboreal.anticheat.web;

import com.lunarboreal.anticheat.AntiCheatModule;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Captures this request's sender only; never installs a global console/log listener. */
public final class GrimCommandRunner {
    private final AntiCheatModule module;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    public GrimCommandRunner(AntiCheatModule module) { this.module = module; }
    public static final class Run {
        final String executionId = UUID.randomUUID().toString(), owner, id, command, permission;
        final long started = System.currentTimeMillis();
        private final List<String> output = new ArrayList<>();
        private int chars;
        volatile boolean dispatched;
        Run(String owner, GrimCommands.Op op, String command) { this.owner = owner; this.id = op.id(); this.permission = op.permission(); this.command = command; }
        synchronized void append(String line) {
            if (System.currentTimeMillis() - started > 300_000 || output.size() >= 200 || chars >= 65_536) return;
            String bounded = line.substring(0, Math.min(line.length(), Math.min(4096, 65_536 - chars)));
            output.add(bounded); chars += bounded.length();
        }
        public synchronized Map<String, Object> json() { return Map.of("ok", dispatched, "id", id, "executionId", executionId, "command", command, "output", List.copyOf(output), "pending", System.currentTimeMillis() - started < 300_000, "started", started, "captureExpiresAt", started + 300_000); }
    }
    public Run find(String id, String owner) {
        Run run = runs.get(id);
        return run != null && run.owner.equals(owner) && System.currentTimeMillis() - run.started <= 600_000 ? run : null;
    }
    public String permission(Run run) { return run.permission; }
    public Run run(String owner, GrimCommands.Op op, String command) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("GrimAC 命令必须在服务器主线程执行");
        Plugin grim = Bukkit.getPluginManager().getPlugin("GrimAC");
        if (grim == null || !grim.isEnabled()) throw new IllegalStateException("GrimAC 未启用");
        runs.values().removeIf(r -> System.currentTimeMillis() - r.started > 600_000);
        if (runs.size() >= 200) throw new IllegalStateException("执行记录已达到上限，请稍后重试");
        Run run = new Run(owner, op, command);
        runs.put(run.executionId, run);
        module.audit(owner, "grim-command-request", run.executionId + " " + command);
        try {
            CommandSender sender = sender(Bukkit.getConsoleSender(), run);
            run.dispatched = execute(Bukkit.getServer(), grim, sender, op, command);
            module.audit(owner, "grim-command-dispatch", run.executionId + " accepted=" + run.dispatched);
            if (!run.dispatched) run.append("GrimAC 未接受该命令，请确认插件和命令注册状态。");
        } catch (RuntimeException e) {
            run.append("命令执行失败：" + e.getClass().getSimpleName());
            module.audit(owner, "grim-command-failed", run.executionId + " " + e.getClass().getSimpleName());
            module.logger().log(java.util.logging.Level.WARNING, "GrimAC Web command failed: " + run.executionId + " " + op.id(), e);
        }
        return run;
    }
    static boolean execute(Server server, Plugin grim, CommandSender sender, GrimCommands.Op op, String commandLine) {
        if (!server.isPrimaryThread()) throw new IllegalStateException("GrimAC 命令必须在服务器主线程执行");
        if (!grim.isEnabled()) throw new IllegalStateException("GrimAC 未启用");
        if (!op.supported() || !(commandLine.equals(op.command()) || commandLine.startsWith(op.command() + " ")))
            throw new IllegalArgumentException("命令与已授权操作不匹配");
        String[] parts = commandLine.split(" ", -1);
        if (!parts[0].equals("grim")) throw new IllegalArgumentException("仅允许 GrimAC 命令");
        // Grim's Cloud command is a Command + PluginIdentifiableCommand, not a PluginCommand.
        // Resolve the plugin namespace first so another plugin cannot take over the bare alias.
        Command command = server.getCommandMap().getCommand(grim.getName().toLowerCase(Locale.ROOT) + ":grim");
        if (command == null) command = server.getCommandMap().getCommand("grim");
        requireOwner(command, grim);
        if (!sender.hasPermission(op.grimPermission())) return false;
        Object target = executionTarget(command, grim);
        if (target instanceof Command bukkit) {
            requireOwner(bukkit, grim);
            if (!bukkit.testPermission(sender)) return false;
            return bukkit.execute(sender, parts[0], Arrays.copyOfRange(parts, 1, parts.length));
        }
        // ModernPaperBrigadier registers Cloud nodes directly: no original BukkitCommand exists.
        // Use the manager retained by CloudBrigadierCommand, with its Bukkit sender mapper and
        // permission-aware executor, exactly as Cloud's legacy BukkitCommand.execute does.
        try {
            if (invoke(target, "owningPlugin") != grim)
                throw new IllegalStateException("GrimAC Cloud 命令归属不匹配");
            Object mapper = invoke(target, "senderMapper");
            Object mapped = invoke(mapper, "map", new Class<?>[]{Object.class}, sender);
            Object executor = invoke(target, "commandExecutor");
            // Cloud's Brigadier preprocessor otherwise reverse-maps our generic sender through
            // Paper getListener(), which only accepts native server senders. Keep the request
            // sender for output; provide the native console source only in this request context.
            Class<?> nativeWrapper = Class.forName(VANILLA_WRAPPER, false, command.getClass().getClassLoader());
            Object nativeSource = nativeWrapper.getMethod("getListener", CommandSender.class).invoke(null, server.getConsoleSender());
            java.util.function.Consumer<Object> customize = context -> {
                try { invoke(context, "store", new Class<?>[]{String.class, Object.class}, "_cloud_brigadier_native_sender", nativeSource); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException("无法准备 GrimAC 命令上下文", e); }
            };
            invoke(executor, "executeCommand", new Class<?>[]{Object.class, String.class, java.util.function.Consumer.class}, mapped, commandLine, customize);
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法直接执行 GrimAC Cloud 命令", e);
        }
    }
    private static void requireOwner(Command command, Plugin grim) {
        if (!(command instanceof PluginIdentifiableCommand owned) || owned.getPlugin() != grim)
            throw new IllegalStateException("GrimAC 命令未注册或归属不匹配");
    }
    private static final String VANILLA_WRAPPER = "org.bukkit.craftbukkit.command.VanillaCommandWrapper";
    private static final String BUKKIT_NODE = "io.papermc.paper.command.brigadier.bukkit.BukkitCommandNode";
    private static final String BUKKIT_HANDLER = BUKKIT_NODE + "$BukkitBrigCommand";
    private static final String CLOUD_HANDLER = "ac.grim.grimac.shaded.incendo.cloud.brigadier.CloudBrigadierCommand";

    private static Object executionTarget(Command command, Plugin grim) {
        Class<?> wrapper = command.getClass();
        while (wrapper != null && !wrapper.getName().equals(VANILLA_WRAPPER)) wrapper = wrapper.getSuperclass();
        if (wrapper == null) return command;
        try {
            Object root = wrapper.getField("vanillaCommand").get(command);
            var queue = new ArrayDeque<Object>();
            var visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            if (root != null) queue.add(root);
            while (!queue.isEmpty() && visited.size() < 512) {
                Object node = queue.remove();
                if (!visited.add(node)) continue;
                if (node.getClass().getName().equals(BUKKIT_NODE))
                    return (Command) invoke(node, "getBukkitCommand");
                Object handler = invoke(node, "getCommand");
                if (handler != null && handler.getClass().getName().equals(BUKKIT_HANDLER))
                    return (Command) field(handler, "command");
                if (handler != null && handler.getClass().getName().equals(CLOUD_HANDLER)) {
                    // Only trust Grim's actual shaded handler, never an identically named foreign class.
                    if (handler.getClass().getClassLoader() != grim.getClass().getClassLoader())
                        throw new IllegalStateException("GrimAC Cloud 命令类加载器不匹配");
                    return field(handler, "commandManager");
                }
                Object redirect = invoke(node, "getRedirect");
                if (redirect != null) queue.add(redirect);
                for (Object child : (Collection<?>) invoke(node, "getChildren")) queue.add(child);
            }
            throw new IllegalStateException("GrimAC 命令节点没有可用的直接执行入口");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法解析 GrimAC Paper 命令节点", e);
        }
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(field.toString());
        return field.get(target);
    }
    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        return invoke(target, name, new Class<?>[0]);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, types);
        // Cloud's mapper/executor implementations and ModernPaperBrigadier are package-private.
        if (!method.canAccess(target) && !method.trySetAccessible()) throw new IllegalAccessException(method.toString());
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }
    static CommandSender sender(ConsoleCommandSender console, Run run) {
        // Paper's CommandSender already extends the native Adventure Audience. Combining it with
        // Grim's relocated Audience is illegal: pointers() has unrelated native/shaded return types.
        // Do not implement ConsoleCommandSender either: BukkitAudiences would redirect its output
        // to the shared console instead of creating an audience for this individual request.
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("equals")) return proxy == args[0];
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("toString")) return "GrimWebSender[" + run.executionId + "]";
            if (name.equals("getName")) return "Web:" + run.owner;
            if (name.equals("name")) return net.kyori.adventure.text.Component.text("Web:" + run.owner);
            if (name.equals("sendMessage") || name.equals("sendRawMessage") || name.equals("sendPlainMessage")) {
                if (args != null) for (Object arg : args) {
                    if (arg instanceof net.kyori.adventure.text.ComponentLike component)
                        run.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component.asComponent()));
                    else if (arg instanceof String text) run.append(org.bukkit.ChatColor.stripColor(text));
                    else if (arg instanceof String[] lines) for (String text : lines) run.append(org.bukkit.ChatColor.stripColor(text));
                }
                return null;
            }
            // Audience defaults must execute against the request sender, never the real console.
            // This also keeps sendRichMessage/sendActionBar overloads from escaping the capture.
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            try { return method.invoke(console, args); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }
}
