package com.lunarboreal.anticheat;

import com.lunarboreal.api.module.ModuleDescription;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class GrimBindingTest {
    @TempDir Path fixtures;

    @ParameterizedTest
    @ValueSource(strings = {"GrimAC", "LightningGrim"})
    void samePluginNameSupportsBothBackendsAndReleasesEventAndReloadBindings(String backend) throws Exception {
        try (var loader = apiFixtures()) {
            var module = new AntiCheatModule();
            module.init(new ModuleDescription("anticheat", "反作弊", "test", "", "", List.of(), List.of(), List.of()),
                    fixtures.toFile(), loader, Logger.getAnonymousLogger());
            var queued = new ArrayList<Runnable>();
            var bridge = new GrimBridge(module, queued::add);
            Plugin plugin = plugin(loader, "git.repo=" + (backend.equals("LightningGrim") ? backend : "Grim") + "\n");
            Object api = loader.loadClass("ac.grim.grimac.api.GrimAPIProvider").getMethod("get").invoke(null);
            Object bus = api.getClass().getField("bus").get(api);
            bridge.bind(plugin);
            assertTrue(bridge.connected());
            assertEquals(backend, bridge.backend());
            assertEquals(backend.equals("LightningGrim"), bridge.lightningGrim());
            assertEquals("2.3.75-71f4e44", bridge.version());
            assertEquals(1, bus.getClass().getField("subscriptions").get(bus));

            Object event = loader.loadClass("ac.grim.grimac.api.event.events.FlagEvent").getConstructor().newInstance();
            var fire = bus.getClass().getMethod("fire", Object.class);
            fire.invoke(bus, event);
            event.getClass().getField("name").set(event, "Second");
            fire.invoke(bus, event);
            assertEquals(List.of("Second", "First"), module.recent().snapshot("").stream().map(RecentFlags.Flag::player).toList());
            assertEquals("KillauraA", module.recent().snapshot("").getFirst().check());

            var results = new ArrayList<String>();
            bridge.reload(results::add);
            assertEquals(0, api.getClass().getField("reloads").get(api));
            queued.removeFirst().run();
            assertEquals(1, api.getClass().getField("reloads").get(api));
            @SuppressWarnings("unchecked")
            var reload = (CompletableFuture<Boolean>) api.getClass().getField("result").get(api);
            bridge.bind(plugin);
            assertEquals(1, bus.getClass().getField("unregistrations").get(bus));
            reload.complete(true);
            assertTrue(results.isEmpty());
            assertFalse(bridge.reloading());
            assertEquals("尚未重载", bridge.reloadStatus());
            bridge.close();
            assertFalse(bridge.connected());
            assertEquals(2, bus.getClass().getField("unregistrations").get(bus));
            fire.invoke(bus, event);
            assertEquals(2, module.recent().captured());
        }
    }

    @Test void absentOrMalformedBuildMetadataRetainsGrimacCompatibility() {
        assertEquals("GrimAC", GrimBridge.backendName(plugin(getClass().getClassLoader(), null)));
        assertEquals("GrimAC", GrimBridge.backendName(plugin(getClass().getClassLoader(), "git.repo=Grim\n")));
        assertEquals("GrimAC", GrimBridge.backendName(plugin(getClass().getClassLoader(), "git.repo=\\uinvalid\n")));
        assertEquals("LightningGrim", GrimBridge.backendName(plugin(getClass().getClassLoader(), "git.repo= lightninggrim \n")));
    }

    private static Plugin plugin(ClassLoader loader, String metadata) {
        return (Plugin) Proxy.newProxyInstance(loader, new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "GrimAC";
            case "isEnabled" -> true;
            case "getResource" -> {
                assertEquals("grimac.properties", args[0]);
                yield metadata == null ? null : new ByteArrayInputStream(metadata.getBytes(StandardCharsets.ISO_8859_1));
            }
            default -> throw new AssertionError(method);
        });
    }

    private URLClassLoader apiFixtures() throws Exception {
        // Public signatures used by both GrimAC and LightningGrim 2.3.75 / GrimAPI 1.6.0.13.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("ac.grim.grimac.api.GrimAbstractAPI", """
                package ac.grim.grimac.api;
                public interface GrimAbstractAPI {
                    ac.grim.grimac.api.event.EventBus getEventBus();
                    String getGrimVersion();
                    java.util.concurrent.CompletableFuture<Boolean> reloadAsync(Object config);
                    default java.util.concurrent.CompletableFuture<Boolean> reloadAsync() { return reloadAsync(null); }
                }
                """);
        sources.put("ac.grim.grimac.api.GrimAPIProvider", """
                package ac.grim.grimac.api;
                public final class GrimAPIProvider {
                    private static final Api API = new Api();
                    public static GrimAbstractAPI get() { return API; }
                    public static final class Api implements GrimAbstractAPI {
                        public final Bus bus = new Bus();
                        public final java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
                        public int reloads;
                        public ac.grim.grimac.api.event.EventBus getEventBus() { return bus; }
                        public String getGrimVersion() { return "2.3.75-71f4e44"; }
                        public java.util.concurrent.CompletableFuture<Boolean> reloadAsync(Object config) { reloads++; return result; }
                    }
                    public static final class Bus implements ac.grim.grimac.api.event.EventBus {
                        public int subscriptions, unregistrations;
                        private ac.grim.grimac.api.event.GrimEventListener listener;
                        private ac.grim.grimac.api.plugin.GrimPlugin owner;
                        public void subscribe(ac.grim.grimac.api.plugin.GrimPlugin owner, Class type, ac.grim.grimac.api.event.GrimEventListener listener) {
                            this.owner=owner; this.listener=listener; subscriptions++;
                        }
                        public void unregisterListener(ac.grim.grimac.api.plugin.GrimPlugin owner, ac.grim.grimac.api.event.GrimEventListener listener) {
                            if (this.owner != owner || this.listener != listener) throw new AssertionError("Wrong listener owner");
                            this.listener=null; unregistrations++;
                        }
                        public void fire(Object event) { if (listener != null) listener.handle(event); }
                    }
                }
                """);
        sources.put("ac.grim.grimac.api.event.EventBus", """
                package ac.grim.grimac.api.event;
                public interface EventBus {
                    void subscribe(ac.grim.grimac.api.plugin.GrimPlugin owner, Class type, GrimEventListener listener);
                    void unregisterListener(ac.grim.grimac.api.plugin.GrimPlugin owner, GrimEventListener listener);
                }
                """);
        sources.put("ac.grim.grimac.api.event.GrimEventListener",
                "package ac.grim.grimac.api.event; public interface GrimEventListener { void handle(Object event); }");
        sources.put("ac.grim.grimac.api.plugin.GrimPlugin",
                "package ac.grim.grimac.api.plugin; public interface GrimPlugin {}");
        sources.put("ac.grim.grimac.api.plugin.BasicGrimPlugin", """
                package ac.grim.grimac.api.plugin;
                public final class BasicGrimPlugin implements GrimPlugin {
                    public BasicGrimPlugin(java.util.logging.Logger log, java.io.File folder, String id, String version, java.util.Collection authors) {}
                }
                """);
        sources.put("ac.grim.grimac.api.GrimUser", """
                package ac.grim.grimac.api;
                public interface GrimUser {
                    java.util.UUID getUniqueId(); String getName(); int getTransactionPing(); String getWorldName();
                }
                """);
        sources.put("ac.grim.grimac.api.AbstractCheck",
                "package ac.grim.grimac.api; public interface AbstractCheck { String getCheckName(); }");
        sources.put("ac.grim.grimac.api.event.events.FlagEvent", """
                package ac.grim.grimac.api.event.events;
                public final class FlagEvent implements ac.grim.grimac.api.GrimUser, ac.grim.grimac.api.AbstractCheck {
                    public String name="First";
                    public ac.grim.grimac.api.GrimUser getUser() { return this; }
                    public ac.grim.grimac.api.AbstractCheck getCheck() { return this; }
                    public String getVerbose() { return "combat"; }
                    public double getViolations() { return 2; }
                    public boolean isCancelled() { return false; }
                    public java.util.UUID getUniqueId() { return new java.util.UUID(0, 1); }
                    public String getName() { return name; }
                    public int getTransactionPing() { return 20; }
                    public String getWorldName() { return "world"; }
                    public String getCheckName() { return "KillauraA"; }
                }
                """);
        var files = new ArrayList<String>();
        for (var source : sources.entrySet()) {
            Path path = fixtures.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue());
            files.add(path.toString());
        }
        var classpath = new LinkedHashSet<String>();
        classpath.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs())
                if (url.getProtocol().equals("file")) classpath.add(Path.of(url.toURI()).toString());
        }
        var args = new ArrayList<String>(List.of("-classpath", String.join(File.pathSeparator, classpath), "-d", fixtures.toString()));
        args.addAll(files);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[]{fixtures.toUri().toURL()}, getClass().getClassLoader());
    }
}
