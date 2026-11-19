package com.lunarboreal.teleport;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.audit.AuditLog;
import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.api.web.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TeleportWebConfigTest {
    @TempDir Path directory;
    private final TeleportModule module = new TeleportModule();
    private final Map<String, WebHandler> routes = new HashMap<>();
    private LunarBorealAPI previousApi;
    private Server previousServer;
    private Object response;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        previousApi = LC.isLoaded() ? LC.get() : null;
        previousServer = Bukkit.getServer();
        World world = new RespawnTestWorld("survival").world;
        set(Bukkit.class, null, "server", proxy(Server.class, (name, args) -> switch (name) {
            case "getWorlds" -> List.of(world);
            case "getWorld" -> "survival".equals(args[0]) ? world : null;
            default -> null;
        }));
        WebRouter[] router = new WebRouter[1];
        router[0] = proxy(WebRouter.class, (name, args) -> {
            routes.put(name.toUpperCase() + " " + args[0], (WebHandler) args[2]);
            return router[0];
        });
        var web = proxy(WebRegistry.class, (name, args) -> {
            if (name.equals("routes")) ((Consumer<WebRouter>) args[2]).accept(router[0]);
            return null;
        });
        var database = proxy(Database.class, (name, args) -> switch (name) {
            case "table" -> args[0];
            case "queryAsync" -> CompletableFuture.completedFuture(List.of());
            default -> null;
        });
        LC.setInstance(proxy(LunarBorealAPI.class, (name, args) -> switch (name) {
            case "web" -> web;
            case "database" -> database;
            case "audit" -> proxy(AuditLog.class, (n, a) -> null);
            default -> null;
        }));
        module.init(new ModuleDescription("teleport", "Teleport", "test", "", "", List.of(), List.of(), List.of()),
                directory.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        set(TeleportModule.class, module, "rtp", new RtpManager(module));
        set(TeleportModule.class, module, "spawns", new SpawnManager(module));
        set(TeleportModule.class, module, "warps", new WarpManager(module));
        module.config();
        new WebSetup(module).register();
    }

    @AfterEach void cleanup() throws Exception {
        LC.setInstance(previousApi);
        set(Bukkit.class, null, "server", previousServer);
    }

    @Test void configExposesNewDefaultsAndPersistsEditsWithReload() throws Exception {
        call("GET /config", Map.of(), null);
        assertEquals(1000, result().get("rtp_default_radius"));
        assertEquals(20, result().get("back_safe_search_radius"));
        call("POST /config", Map.of("rtp_default_radius", 250, "back_safe_search_radius", 24,
                "rtp_avoid_biomes", "ocean, river\ndesert", "rtp_max_attempts", 30), null);
        var disk = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(250, module.rtp().defaultRadius());
        assertEquals(24, disk.getInt("back.safe-search-radius"));
        assertEquals(List.of("ocean", "river", "desert"), disk.getStringList("rtp.avoid-biomes"));
        assertEquals(30, disk.getInt("rtp.max-attempts"));
    }

    @Test @SuppressWarnings("unchecked") void listsAndConfiguresAnUnconfiguredLoadedWorld() throws Exception {
        call("GET /rtp", Map.of(), null);
        var row = ((List<Map<String, Object>>) result().get("items")).getFirst();
        assertEquals("survival", row.get("world"));
        assertEquals(true, row.get("enabled"));
        assertEquals(true, row.get("center_on_player"));
        assertEquals(0, row.get("min_radius"));
        assertEquals(1000, row.get("max_radius"));
        call("POST /rtp/{world}/edit", Map.of("enabled", false, "center_on_player", false,
                "center_x", 200, "center_z", -100, "min_radius", 20, "max_radius", 300), "survival");
        assertTrue(module.rtp().worlds().isEmpty());
        assertEquals(new RtpManager.Settings(200, -100, 20, 300),
                module.rtp().settings(Bukkit.getWorld("survival")));
        assertFalse(module.config().getBoolean("rtp.worlds.survival.center-on-player"));
    }

    @Test void invalidRadiiDoNotPartiallyMutateConfiguration() {
        String original = module.config().saveToString();
        for (Object radius : List.of(0, -1, 1.5, "NaN", 30_000_001)) {
            assertThrows(HttpException.class, () -> call("POST /config",
                    Map.of("rtp_default_radius", radius, "rtp_avoid_lands", false), null));
            assertEquals(original, module.config().saveToString());
        }
        assertThrows(HttpException.class, () -> call("POST /rtp/{world}/edit",
                Map.of("center_x", 500, "min_radius", 1000, "max_radius", 20), "survival"));
        assertEquals(original, module.config().saveToString());
        assertThrows(HttpException.class, () -> call("POST /config", Map.of("back_safe_search_radius", 19), null));
    }

    private void call(String route, Map<String, Object> body, String world) throws Exception {
        response = null;
        routes.get(route).handle(proxy(WebContext.class, (name, args) -> switch (name) {
            case "sync" -> ((Callable<?>) args[0]).call();
            case "bodyMap" -> body;
            case "pathParam" -> world;
            case "user" -> proxy(WebUser.class, (n, a) -> n.equals("username") ? "admin" : null);
            case "json" -> { response = args[0]; yield null; }
            case "ok" -> null;
            case "page" -> { response = Map.of("total", args[0], "items", args[1]); yield null; }
            default -> null;
        }));
    }

    @SuppressWarnings("unchecked") private Map<String, Object> result() { return (Map<String, Object>) response; }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @FunctionalInterface private interface Answer { Object answer(String name, Object[] args) throws Exception; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            Object value = answer.answer(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive()) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == double.class) return 0d;
            if (method.getReturnType() == float.class) return 0f;
            if (method.getReturnType() == void.class) return null;
            return 0;
        });
    }
}
