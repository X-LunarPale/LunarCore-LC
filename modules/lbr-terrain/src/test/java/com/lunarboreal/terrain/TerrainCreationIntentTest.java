package com.lunarboreal.terrain;

import com.lunarboreal.api.LBR;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.audit.AuditLog;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.terrain.nms.NativeTectonicBridge;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("Temporarily replaces the Bukkit and LBR global entry points")
class TerrainCreationIntentTest {
    @TempDir Path root;

    @Test void rollsBackAnUnwrittenCreationIntentAndAllowsPersistenceOnRetry() throws Exception {
        Path levelDirectory = Files.createDirectory(root.resolve("world"));
        Path moduleDirectory = Files.createDirectory(root.resolve("terrain"));
        Path registrations = Files.createDirectory(moduleDirectory.resolve("worlds.yml"));
        Path blocker = registrations.resolve("keep");
        Files.writeString(blocker, "An existing nonempty directory must not be replaced.");
        Path target = TerrainWorldSpec.dimensionPath(levelDirectory, "alpine");
        TerrainModule module = new TerrainModule();
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        module.init(new ModuleDescription("terrain", "Terrain", "test",
                TerrainModule.class.getName(), "", List.of(), List.of(), List.of()),
                moduleDirectory.toFile(), getClass().getClassLoader(), logger);
        module.setEnabledState(true);
        setField(module, "registrations", registrations);
        setField(module, "storageReady", true);
        setField(module, "bridge", new NativeTectonicBridge());
        Map<String, TerrainWorldSpec> worlds = field(module, "worlds");
        worlds.put("alpine", new TerrainWorldSpec("alpine", Long.MAX_VALUE, TerrainOptions.defaults(), true));
        Set<String> established = field(module, "established");
        Map<String, String> states = field(module, "states");
        Map<String, String> errors = field(module, "errors");
        Field bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        Object previousServer = bukkitServer.get(null);
        LunarBorealAPI previousApi = LBR.isLoaded() ? LBR.get() : null;
        AuditLog audit = new AuditLog() {
            @Override public void log(String owner, String actor, String action, String detail) {}
            @Override public List<Entry> recent(String owner, int offset, int limit) { return List.of(); }
        };
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[]{Server.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getLevelDirectory" -> levelDirectory;
                    case "isPrimaryThread" -> true;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        LunarBorealAPI api = (LunarBorealAPI) Proxy.newProxyInstance(LunarBorealAPI.class.getClassLoader(),
                new Class<?>[]{LunarBorealAPI.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("audit")) return audit;
                    throw new UnsupportedOperationException(method.toString());
                });
        Method next = TerrainModule.class.getDeclaredMethod("next");
        next.setAccessible(true);
        try {
            bukkitServer.set(null, server);
            LBR.setInstance(api);
            enqueue(module, "alpine");
            next.invoke(module);

            assertEquals("failed", states.get("alpine"));
            assertFalse(errors.get("alpine").isBlank());
            assertFalse(established.contains("alpine"), "A failed registration write must not mark a world as created");
            assertFalse(Files.exists(target), "Persistence failure must occur before touching world storage");
            assertEquals("An existing nonempty directory must not be replaced.", Files.readString(blocker));

            Files.delete(blocker);
            Files.delete(registrations);
            enqueue(module, "alpine");
            next.invoke(module);

            // The uninitialized bridge stops the retry after its intent is saved, without starting a server.
            assertEquals("Native Tectonic is not initialized", errors.get("alpine"));
            assertTrue(established.contains("alpine"));
            assertTrue(Files.isRegularFile(registrations));
            YamlConfiguration saved = new YamlConfiguration();
            saved.load(registrations.toFile());
            assertTrue(saved.getBoolean("worlds.alpine.created"));
            assertEquals(Long.toString(Long.MAX_VALUE), saved.getString("worlds.alpine.seed"));
            assertFalse(Files.exists(target));
        } finally {
            LBR.setInstance(previousApi);
            bukkitServer.set(null, previousServer);
        }
    }

    private static void enqueue(TerrainModule module, String name) throws Exception {
        Class<?> requestType = Class.forName(TerrainModule.class.getName() + "$LoadRequest");
        Constructor<?> constructor = requestType.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        ArrayDeque<Object> queue = field(module, "queue");
        queue.addLast(constructor.newInstance(name, "test"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(TerrainModule module, String name) throws Exception {
        Field field = TerrainModule.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(module);
    }

    private static void setField(TerrainModule module, String name, Object value) throws Exception {
        Field field = TerrainModule.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(module, value);
    }
}
