package com.lunarboreal.anticheat.web;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class GrimCommandRunnerTest {
    @Test void executesOwnedCloudStyleCommandWithoutPaperDispatchAndCapturesLateReplies() {
        Plugin grim = plugin("GrimAC");
        var command = new OwnedCommand(grim);
        var bareAlias = new OwnedCommand(plugin("Other"));
        var run = new GrimCommandRunner.Run("tester", GrimCommands.find("sendalert"), "grim sendalert hello  world");
        CommandSender sender = requestSender(run, true);
        Server server = server(Map.of("grimac:grim", command, "grim", bareAlias), true);
        assertTrue(GrimCommandRunner.execute(server, grim, sender, GrimCommands.find("sendalert"), run.command));
        assertSame(sender, command.sender);
        assertEquals("grim", command.label);
        assertArrayEquals(new String[]{"sendalert", "hello", "", "world"}, command.args);
        assertNull(bareAlias.sender);
        command.sender.sendMessage("late reply");
        assertEquals(java.util.List.of("executed", "late reply"), run.json().get("output"));
    }

    @Test void permitsOwnedBareAliasButRejectsMissingForeignAndUnidentifiedCommands() {
        Plugin grim = plugin("GrimAC");
        var run = new GrimCommandRunner.Run("tester", GrimCommands.find("version"), "grim version");
        CommandSender sender = requestSender(run, true);
        assertTrue(GrimCommandRunner.execute(server(Map.of("grim", new OwnedCommand(grim)), true), grim, sender,
                GrimCommands.find("version"), run.command));
        Command unidentified = new Command("grim") {
            @Override public boolean execute(CommandSender s, String label, String[] args) { fail("unowned command executed"); return true; }
        };
        for (Map<String, Command> registered : java.util.List.<Map<String, Command>>of(Map.of(),
                Map.of("grim", new OwnedCommand(plugin("GrimAC"))), Map.of("grim", unidentified),
                Map.of("grimac:grim", new OwnedCommand(plugin("Other")), "grim", new OwnedCommand(grim)))) {
            assertThrows(IllegalStateException.class, () -> GrimCommandRunner.execute(server(registered, true), grim, sender,
                    GrimCommands.find("version"), run.command));
        }
    }

    @Test void refusesOffThreadMismatchedOperationAndDeniedPermissionsBeforeExecution() {
        Plugin grim = plugin("GrimAC");
        var command = new OwnedCommand(grim);
        var run = new GrimCommandRunner.Run("tester", GrimCommands.find("version"), "grim version");
        CommandSender sender = requestSender(run, true);
        assertThrows(IllegalStateException.class, () -> GrimCommandRunner.execute(server(Map.of("grim", command), false),
                grim, sender, GrimCommands.find("version"), run.command));
        assertThrows(IllegalArgumentException.class, () -> GrimCommandRunner.execute(server(Map.of("grim", command), true),
                grim, sender, GrimCommands.find("version"), "grim reload"));
        assertFalse(GrimCommandRunner.execute(server(Map.of("grim", command), true), grim, requestSender(run, false),
                GrimCommands.find("version"), run.command));
        command.allowed = false;
        assertFalse(GrimCommandRunner.execute(server(Map.of("grim", command), true), grim, sender,
                GrimCommands.find("version"), run.command));
        assertNull(command.sender);
    }

    @Test void unwrapsModernPaperCloudGraphAndKeepsRequestSender() throws Exception {
        try (var loader = paperFixtures()) {
            Plugin grim = (Plugin) Proxy.newProxyInstance(loader, new Class<?>[]{Plugin.class}, (p, m, a) -> switch (m.getName()) {
                case "getName" -> "GrimAC";
                case "isEnabled" -> true;
                default -> throw new AssertionError(m);
            });
            var manager = new CloudManager(grim);
            Object handler = loader.loadClass("ac.grim.grimac.shaded.incendo.cloud.brigadier.CloudBrigadierCommand")
                    .getConstructor(Object.class).newInstance(manager);
            var root = new GraphNode(null);
            var redirect = new GraphNode(null);
            root.redirect = redirect;
            redirect.redirect = root; // Identity cycle must not prevent finding a child handler.
            redirect.children.add(new GraphNode(handler));
            Command wrapper = (Command) loader.loadClass("org.bukkit.craftbukkit.command.VanillaCommandWrapper")
                    .getConstructor(Plugin.class, Object.class).newInstance(grim, root);
            var run = new GrimCommandRunner.Run("tester", GrimCommands.find("sendalert"), "grim sendalert hello  world");
            CommandSender sender = requestSender(run, true);
            assertTrue(GrimCommandRunner.execute(server(Map.of("grimac:grim", wrapper), true), grim, sender,
                    GrimCommands.find("sendalert"), run.command));
            assertSame(sender, manager.mapped);
            assertEquals(run.command, manager.input);
            manager.mapped.sendMessage("late cloud reply");
            assertEquals(java.util.List.of("cloud reply", "late cloud reply"), run.json().get("output"));
            manager.plugin = plugin("Other");
            assertThrows(IllegalStateException.class, () -> GrimCommandRunner.execute(server(Map.of("grim", wrapper), true),
                    grim, sender, GrimCommands.find("sendalert"), run.command));
            manager.plugin = grim;
            manager.input = null;
            assertFalse(GrimCommandRunner.execute(server(Map.of("grim", wrapper), true), grim, requestSender(run, false),
                    GrimCommands.find("sendalert"), run.command));
            assertNull(manager.input);
            root.redirect = root;
            assertThrows(IllegalStateException.class, () -> GrimCommandRunner.execute(server(Map.of("grim", wrapper), true),
                    grim, sender, GrimCommands.find("sendalert"), run.command));
        }
    }

    @Test void unwrapsPaperBukkitHandlerAndRechecksOriginalOwnership() throws Exception {
        try (var loader = paperFixtures()) {
            Plugin grim = plugin("GrimAC");
            var original = new OwnedCommand(grim);
            var handlerClass = loader.loadClass("io.papermc.paper.command.brigadier.bukkit.BukkitCommandNode$BukkitBrigCommand");
            Object handler = handlerClass.getConstructor(Command.class).newInstance(original);
            var wrapperClass = loader.loadClass("org.bukkit.craftbukkit.command.VanillaCommandWrapper");
            Command wrapper = (Command) wrapperClass.getConstructor(Plugin.class, Object.class).newInstance(grim, new GraphNode(handler));
            var run = new GrimCommandRunner.Run("tester", GrimCommands.find("version"), "grim version");
            assertTrue(GrimCommandRunner.execute(server(Map.of("grim", wrapper), true), grim, requestSender(run, true),
                    GrimCommands.find("version"), run.command));
            assertNotNull(original.sender);
            Object foreign = handlerClass.getConstructor(Command.class).newInstance(new OwnedCommand(plugin("Other")));
            Command bad = (Command) wrapperClass.getConstructor(Plugin.class, Object.class).newInstance(grim, new GraphNode(foreign));
            assertThrows(IllegalStateException.class, () -> GrimCommandRunner.execute(server(Map.of("grim", bad), true),
                    grim, requestSender(run, true), GrimCommands.find("version"), run.command));
        }
    }

    public static final class GraphNode {
        private final Object handler;
        Object redirect;
        final java.util.List<Object> children = new java.util.ArrayList<>();
        GraphNode(Object handler) { this.handler = handler; }
        public Object getCommand() { return handler; }
        public Object getRedirect() { return redirect; }
        public java.util.Collection<Object> getChildren() { return children; }
    }
    // Deliberately non-public, like Cloud's actual mapper/executor implementation classes.
    private static final class CloudManager {
        Plugin plugin;
        CommandSender mapped;
        String input;
        CloudManager(Plugin plugin) { this.plugin = plugin; }
        public Plugin owningPlugin() { return plugin; }
        public Object senderMapper() { return new Object() {
            public Object map(Object sender) { mapped = (CommandSender) sender; return sender; }
        }; }
        public Object commandExecutor() { return new Object() {
            public java.util.concurrent.CompletableFuture<Object> executeCommand(Object sender, String line, java.util.function.Consumer<Object> customize) {
                var context = new ProbeContext();
                customize.accept(context);
                assertNotNull(context.source, "Native source must be supplied before Cloud preprocessing");
                assertSame(mapped, sender); input = line; mapped.sendMessage("cloud reply");
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        }; }
    }

    public static final class ProbeContext {
        Object source;
        public void store(String key, Object value) {
            assertEquals("_cloud_brigadier_native_sender", key);
            source = value;
        }
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path fixtures;
    private java.net.URLClassLoader paperFixtures() throws Exception {
        // Exact names/field visibility from Paper 26.3-202 and Grim 2.3.75 bytecode. A wrapper's
        // execute is a tripwire: the regression must exercise graph resolution before execution.
        Map<String, String> sources = Map.of(
                "org.bukkit.craftbukkit.command.VanillaCommandWrapper", """
                    package org.bukkit.craftbukkit.command;
                    public class VanillaCommandWrapper extends org.bukkit.command.Command implements org.bukkit.command.PluginIdentifiableCommand {
                        public static Object getListener(org.bukkit.command.CommandSender sender) { return new Object(); }
                        public final Object vanillaCommand;
                        private final org.bukkit.plugin.Plugin plugin;
                        public VanillaCommandWrapper(org.bukkit.plugin.Plugin plugin, Object node) { super("grim"); this.plugin=plugin; vanillaCommand=node; }
                        public org.bukkit.plugin.Plugin getPlugin() { return plugin; }
                        public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) { throw new AssertionError("Cannot make request sender a vanilla command listener"); }
                    }
                    """,
                "ac.grim.grimac.shaded.incendo.cloud.brigadier.CloudBrigadierCommand", """
                    package ac.grim.grimac.shaded.incendo.cloud.brigadier;
                    public final class CloudBrigadierCommand {
                        private final Object commandManager;
                        public CloudBrigadierCommand(Object manager) { commandManager=manager; }
                    }
                    """,
                "io.papermc.paper.command.brigadier.bukkit.BukkitCommandNode", """
                    package io.papermc.paper.command.brigadier.bukkit;
                    public class BukkitCommandNode {
                        public static class BukkitBrigCommand {
                            private final org.bukkit.command.Command command;
                            public BukkitBrigCommand(org.bukkit.command.Command command) { this.command=command; }
                        }
                    }
                    """);
        var files = new java.util.ArrayList<String>();
        for (var source : sources.entrySet()) {
            var path = fixtures.resolve(source.getKey().replace('.', '/') + ".java");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, source.getValue());
            files.add(path.toString());
        }
        var classpath = new java.util.LinkedHashSet<String>();
        classpath.add(System.getProperty("java.class.path"));
        // Gradle's test worker keeps dependencies in a URLClassLoader, outside java.class.path.
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) for (var url : urls.getURLs()) {
                if (url.getProtocol().equals("file")) classpath.add(java.nio.file.Path.of(url.toURI()).toString());
            }
        }
        var args = new java.util.ArrayList<String>(java.util.List.of("-classpath", String.join(java.io.File.pathSeparator, classpath), "-d", fixtures.toString()));
        args.addAll(files);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return new java.net.URLClassLoader(new java.net.URL[]{fixtures.toUri().toURL()}, getClass().getClassLoader());
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (p, m, a) -> switch (m.getName()) {
            case "getName" -> name;
            case "isEnabled" -> true;
            default -> throw new AssertionError("Unexpected plugin call: " + m);
        });
    }

    private static Server server(Map<String, Command> commands, boolean primary) {
        CommandMap map = (CommandMap) Proxy.newProxyInstance(CommandMap.class.getClassLoader(), new Class<?>[]{CommandMap.class}, (p, m, a) -> {
            if (m.getName().equals("getCommand")) return commands.get(a[0]);
            throw new AssertionError("Must resolve commands without dispatch: " + m);
        });
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (p, m, a) -> switch (m.getName()) {
            case "isPrimaryThread" -> primary;
            case "getCommandMap" -> map;
            case "getConsoleSender" -> Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(), new Class<?>[]{ConsoleCommandSender.class}, (cp, cm, ca) -> { throw new AssertionError("Native source must never receive request output"); });
            default -> throw new AssertionError("Must not enter Paper dispatch: " + m);
        });
    }

    private static CommandSender requestSender(GrimCommandRunner.Run run, boolean permission) {
        ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class}, (p, m, a) -> {
                    if (m.getName().equals("hasPermission")) return permission;
                    throw new AssertionError("Request output escaped to console: " + m);
                });
        return GrimCommandRunner.sender(console, run);
    }

    // Mirrors Grim's public Bukkit contract without depending on its relocated implementation.
    private static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;
        private boolean allowed = true;
        private CommandSender sender;
        private String label;
        private String[] args;
        OwnedCommand(Plugin plugin) { super("grim"); this.plugin = plugin; }
        @Override public Plugin getPlugin() { return plugin; }
        @Override public boolean testPermission(CommandSender sender) { return allowed; }
        @Override public boolean execute(CommandSender sender, String label, String[] args) {
            this.sender = sender; this.label = label; this.args = args;
            sender.sendMessage("executed");
            return true;
        }
    }

    @Test void requestSenderProxyUsesOnlyTheNativeBukkitSenderInterface() {
        var console = (ConsoleCommandSender) Proxy.newProxyInstance(ConsoleCommandSender.class.getClassLoader(),
                new Class<?>[]{ConsoleCommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission") || method.getName().equals("isOp")) return true;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        var run = new GrimCommandRunner.Run("web:tester", GrimCommands.find("version"), "grim version");
        CommandSender sender = GrimCommandRunner.sender(console, run);
        assertTrue(Proxy.isProxyClass(sender.getClass()));
        assertArrayEquals(new Class<?>[]{CommandSender.class}, sender.getClass().getInterfaces());
        assertFalse(sender instanceof ConsoleCommandSender);
        assertEquals("Web:web:tester", sender.getName());
        assertTrue(sender.hasPermission("grim.version"));
        sender.sendMessage("test output");
        sender.sendMessage(net.kyori.adventure.text.Component.text("component output"));
        sender.sendRichMessage("<red>rich output</red>");
        assertEquals(java.util.List.of("test output", "component output", "rich output"), run.json().get("output"));
        assertNotNull(sender.pointers());
    }
}
