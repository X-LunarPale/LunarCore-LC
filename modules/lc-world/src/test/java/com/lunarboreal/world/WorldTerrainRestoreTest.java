package com.lunarboreal.world;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.api.service.ServiceRegistry;
import com.lunarboreal.api.world.TerrainWorldLifecycle;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class WorldTerrainRestoreTest {
    @TempDir Path temp;
    private final WorldModule module = new WorldModule();
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<Runnable> timeouts = new ArrayList<>();
    private final List<String> nativeLoads = new ArrayList<>();
    private final List<String> ordinaryLoads = new ArrayList<>();
    private final Map<Object, World> loaded = new HashMap<>();
    private Server previousServer;
    private LunarBorealAPI previousApi;
    private TerrainWorldLifecycle terrain;

    @BeforeEach void setup() throws Exception {
        previousServer = Bukkit.getServer();
        previousApi = LC.isLoaded() ? LC.get() : null;
        Path container = Files.createDirectories(temp.resolve("server"));
        Path primary = Files.createDirectories(container.resolve("world"));
        Files.createFile(primary.resolve("level.dat"));
        Path data = Files.createDirectories(temp.resolve("config"));
        var scheduler = proxy(BukkitScheduler.class, (name, args) -> {
            if (name.equals("runTask")) tasks.add((Runnable) args[1]);
            if (name.equals("runTaskLater")) timeouts.add((Runnable) args[1]);
            return proxy(BukkitTask.class, (n, a) -> null);
        });
        var unsafe = proxy(UnsafeValues.class, (name, args) -> name.equals("getMainLevelName") ? "world" : null);
        set(Bukkit.class, null, "server", proxy(Server.class, (name, args) -> switch (name) {
            case "getWorldContainer" -> container.toFile();
            case "getScheduler" -> scheduler;
            case "getUnsafe" -> unsafe;
            case "getWorld" -> loaded.get(args[0]);
            case "isPrimaryThread" -> true;
            case "createWorld" -> { ordinaryLoads.add(((WorldCreator) args[0]).name()); yield null; }
            default -> null;
        }));
        terrain = new TerrainWorldLifecycle() {
            @Override public boolean manages(String keyOrName) {
                return Set.of("jixian", "lc_terrain:jixian", "lbr_terrain:jixian",
                        "lc_terrain_jixian", "lbr_terrain_jixian").contains(keyOrName);
            }
            @Override public void load(String keyOrName, String actor) { nativeLoads.add(keyOrName); }
            @Override public void unload(String keyOrName, String actor) { fail("Unexpected unload"); }
            @Override public boolean autoLoad(String keyOrName) { return true; }
            @Override public void autoLoad(String keyOrName, boolean enabled, String actor) { fail("Unexpected setting change"); }
        };
        var services = proxy(ServiceRegistry.class, (name, args) -> name.equals("get")
                ? Optional.ofNullable(terrain) : null);
        var plugin = proxy(Plugin.class, (name, args) -> null);
        LC.setInstance(proxy(LunarBorealAPI.class, (name, args) -> switch (name) {
            case "services" -> services;
            case "plugin" -> plugin;
            default -> null;
        }));
        module.init(new ModuleDescription("world", "World", "test", "", "", List.of(), List.of("terrain"), List.of()),
                data.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        module.setEnabledState(true);
        set(WorldModule.class, module, "file", data.resolve("worlds.yml"));
        set(WorldModule.class, module, "storageReady", true);
    }

    @AfterEach void cleanup() throws Exception {
        module.cleanupInternal();
        LC.setInstance(previousApi);
        set(Bukkit.class, null, "server", previousServer);
    }

    @Test void recognizesTheIncorrectPersistedMinecraftAliasAsNativeTerrain() {
        assertEquals("lc_terrain:jixian", WorldModule.terrainKey(
                "lc_terrain_jixian", "dimensions/minecraft/lc_terrain_jixian", null));
        assertEquals("lbr_terrain:jixian", WorldModule.terrainKey(
                "lbr_terrain_jixian", "dimensions/minecraft/lbr_terrain_jixian", null));
    }

    @Test void retainsNativeDimensionKeysAndUsesRegisteredBareNames() {
        assertEquals("lc_terrain:jixian", WorldModule.terrainKey(
                "display_name", "dimensions/lc_terrain/jixian", null));
        assertEquals("lbr_terrain:jixian", WorldModule.terrainKey(
                "display_name", "dimensions/lbr_terrain/jixian", null));
        assertEquals("lc_terrain:jixian", WorldModule.terrainKey("jixian", null, terrain));
        assertNull(WorldModule.terrainKey("world", "dimensions/minecraft/overworld", terrain));
        assertNull(WorldModule.terrainKey("ordinary", null, terrain));
    }

    @Test void onlyMatchesNativeNamespacesForTheSameDimension() {
        assertTrue(WorldModule.sameTerrainWorld("lc_terrain:jixian", world("lc_terrain:jixian")));
        assertTrue(WorldModule.sameTerrainWorld("lc_terrain:jixian", world("lbr_terrain:jixian")));
        assertFalse(WorldModule.sameTerrainWorld("lc_terrain:jixian", world("minecraft:lc_terrain_jixian")));
        assertFalse(WorldModule.sameTerrainWorld("lc_terrain:jixian", world("minecraft:jixian")));
        assertFalse(WorldModule.sameTerrainWorld("lc_terrain:jixian", world("lc_terrain:other")));
    }

    @Test void startupRestoresTheOldAliasThroughTerrainWithoutCreatingAnOrdinaryWorld() {
        WorldOptions options = registered("lc_terrain_jixian", "dimensions/minecraft/lc_terrain_jixian");
        module.queueLoad(options, "startup");
        tasks.removeFirst().run();
        assertEquals(List.of("lc_terrain:jixian"), nativeLoads);
        assertTrue(ordinaryLoads.isEmpty());
        assertEquals("queued", module.states.get(options.name()));
        assertEquals(1, timeouts.size());
    }

    @Test void missingTerrainServicePreservesTheRegistrationAndNeverFallsBack() {
        terrain = null;
        WorldOptions options = registered("lc_terrain_jixian", "dimensions/minecraft/lc_terrain_jixian");
        module.queueLoad(options, "startup");
        tasks.removeFirst().run();
        assertTrue(nativeLoads.isEmpty());
        assertTrue(ordinaryLoads.isEmpty());
        assertEquals("failed", module.states.get(options.name()));
        assertTrue(module.errors.get(options.name()).contains("地形模块"));
        assertSame(options, module.worlds.get(options.name()));
        assertEquals("dimensions/minecraft/lc_terrain_jixian", module.dimensionPaths.get(options.name()));
    }

    @Test void unknownNativeRegistrationIsNotRegeneratedAsVanilla() {
        WorldOptions options = registered("lc_terrain_unknown", "dimensions/lc_terrain/unknown");
        module.queueLoad(options, "startup");
        tasks.removeFirst().run();
        assertTrue(nativeLoads.isEmpty());
        assertTrue(ordinaryLoads.isEmpty());
        assertEquals("failed", module.states.get(options.name()));
        assertTrue(module.errors.get(options.name()).contains("未在地形模块登记"));
    }

    @Test void rejectsAnAlreadyLoadedMinecraftWorldWithTheNativeDisplayName() {
        WorldOptions options = registered("lc_terrain_jixian", "dimensions/minecraft/lc_terrain_jixian");
        loaded.put(options.name(), world("minecraft:lc_terrain_jixian"));
        module.queueLoad(options, "startup");
        tasks.removeFirst().run();
        assertTrue(nativeLoads.isEmpty());
        assertTrue(ordinaryLoads.isEmpty());
        assertEquals("failed", module.states.get(options.name()));
        assertTrue(module.errors.get(options.name()).contains("同名普通世界"));
    }

    @Test void pendingNativeRestoreOnlyRespondsToTheCorrectWorldLoadEvent() {
        WorldOptions options = registered("lc_terrain_jixian", "dimensions/minecraft/lc_terrain_jixian");
        module.queueLoad(options, "startup");
        tasks.removeFirst().run();
        module.worldLoaded(new WorldLoadEvent(world("minecraft:lc_terrain_jixian")));
        module.worldLoaded(new WorldLoadEvent(world("lc_terrain:other")));
        assertTrue(tasks.isEmpty());
        module.worldLoaded(new WorldLoadEvent(world("lc_terrain:jixian")));
        assertEquals(1, tasks.size());
        assertEquals("queued", module.states.get(options.name()), "Settings wait until native creation has completed");
        assertTrue(ordinaryLoads.isEmpty());
    }

    @Test void unfinishedNativeRestoreHasABoundedFailureInsteadOfVanillaFallback() {
        WorldOptions options = registered("lc_terrain_jixian", "dimensions/minecraft/lc_terrain_jixian");
        module.queueLoad(options, "startup");
        tasks.removeFirst().run();
        timeouts.removeFirst().run();
        assertEquals("failed", module.states.get(options.name()));
        assertTrue(module.errors.get(options.name()).contains("加载未完成"));
        assertTrue(ordinaryLoads.isEmpty());
    }

    @Test void ordinaryWorldsStillUseTheirExistingWorldCreatorPath() {
        WorldOptions options = registered("ordinary", null);
        module.imported.remove(options.name());
        module.queueLoad(options, "startup");
        tasks.removeFirst().run();
        assertEquals(List.of("ordinary"), ordinaryLoads);
        assertTrue(nativeLoads.isEmpty());
    }

    private WorldOptions registered(String name, String dimensionPath) {
        WorldOptions options = WorldOptions.parse(name, Map.of(), null);
        module.worlds.put(name, options);
        module.imported.add(name);
        module.saveRoots.put(name, dimensionPath == null ? name : "world");
        if (dimensionPath != null) module.dimensionPaths.put(name, dimensionPath);
        module.states.put(name, "registered");
        module.errors.put(name, "");
        return options;
    }

    private World world(String key) {
        return proxy(World.class, (name, args) -> switch (name) {
            case "getKey" -> NamespacedKey.fromString(key);
            case "getName" -> key.replace(':', '_');
            default -> null;
        });
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @FunctionalInterface private interface Answer { Object answer(String method, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return type.getSimpleName();
            Object result = answer.answer(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive()) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == double.class) return 0d;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == void.class) return null;
            return 0;
        });
    }
}
