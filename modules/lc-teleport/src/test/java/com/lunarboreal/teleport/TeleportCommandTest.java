package com.lunarboreal.teleport;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.audit.AuditLog;
import com.lunarboreal.api.command.CommandRegistry;
import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.api.module.LcModule;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.api.service.LandService;
import com.lunarboreal.api.service.ServiceRegistry;
import com.lunarboreal.teleport.command.RtpNpcCommand;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TeleportCommandTest {
    @TempDir Path temp;
    private final TeleportModule module = new TeleportModule();
    private final YamlConfiguration config = new YamlConfiguration();
    private final Map<String, LcCommand> commands = new HashMap<>();
    private final Map<String, World> worlds = new LinkedHashMap<>();
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final List<String> messages = new ArrayList<>();
    private final List<Location> destinations = new ArrayList<>();
    private Server previousServer;
    private LunarBorealAPI previousApi;
    private AutoCloseable materials;
    private TeleportManager teleports;
    private ConsoleCommandSender sender;
    private int looseLookups;
    private int chunkLoads;
    private int scheduledTasks;
    private int economyCalls;
    private int landChecks;
    private boolean allowLand = true;

    @BeforeEach void setup() throws Exception {
        previousServer = Bukkit.getServer();
        previousApi = LC.isLoaded() ? LC.get() : null;
        materials = RespawnTestWorld.materials();
        worlds.put("survival", world("survival"));
        worlds.put("creative", world("creative"));
        players.put("Alice", player("Alice", true));
        players.put("Bob", player("Bob", true));
        players.put("Offline", player("Offline", false));
        sender = proxy(ConsoleCommandSender.class, (name, args) -> switch (name) {
            case "getName" -> "CONSOLE";
            case "hasPermission" -> true;
            default -> null;
        });
        var scheduler = proxy(BukkitScheduler.class, (name, args) -> {
            if (name.startsWith("runTask")) {
                scheduledTasks++;
                return proxy(BukkitTask.class, (n, a) -> null);
            }
            return null;
        });
        set(Bukkit.class, null, "server", proxy(Server.class, (name, args) -> switch (name) {
            case "getWorld" -> args[0] instanceof UUID id
                    ? worlds.values().stream().filter(w -> w.getUID().equals(id)).findFirst().orElse(null)
                    : worlds.get(args[0]);
            case "getWorlds" -> List.copyOf(worlds.values());
            case "getPlayerExact" -> players.get(args[0]);
            case "getPlayer" -> { looseLookups++; yield players.get("Alice"); }
            case "getOnlinePlayers" -> players.values().stream().filter(Player::isOnline).toList();
            case "getScheduler" -> scheduler;
            case "isPrimaryThread" -> true;
            default -> null;
        }));
        var economy = proxy(EconomyService.class, (name, args) -> {
            economyCalls++;
            return name.equals("withdraw") ? EconomyService.Result.fail(java.math.BigDecimal.ZERO, "denied") : null;
        });
        var land = proxy(LandService.class, (name, args) -> switch (name) {
            case "landAt" -> Optional.empty();
            case "can" -> { landChecks++; yield allowLand; }
            default -> null;
        });
        var services = proxy(ServiceRegistry.class, (name, args) -> {
            if (!name.equals("get")) return null;
            if (args[0] == EconomyService.class) return Optional.of(economy);
            if (args[0] == LandService.class) return Optional.of(land);
            return Optional.empty();
        });
        var messageService = proxy(MessageService.class, (name, args) -> {
            if (name.equals("send")) messages.add((String) args[1]);
            return null;
        });
        var registry = proxy(CommandRegistry.class, (name, args) -> {
            if (name.equals("register")) commands.put(((LcCommand) args[1]).name(), (LcCommand) args[1]);
            return null;
        });
        var audit = proxy(AuditLog.class, (name, args) -> null);
        LC.setInstance(proxy(LunarBorealAPI.class, (name, args) -> switch (name) {
            case "commands" -> registry;
            case "messages" -> messageService;
            case "services" -> services;
            case "audit" -> audit;
            case "isPaper" -> true;
            default -> null;
        }));
        module.init(new ModuleDescription("teleport", "Teleport", "test", "", "", List.of(), List.of(), List.of()),
                temp.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        set(LcModule.class, module, "config", config);
        module.setEnabledState(true);
        config.set("back.persist", false);
        config.set("teleport.warmup", 0);
        teleports = new TeleportManager(module);
        set(TeleportModule.class, module, "teleports", teleports);
        set(TeleportModule.class, module, "rtp", new RtpManager(module));
        com.lunarboreal.teleport.command.SpawnCommands.register(module);
    }

    @AfterEach void cleanup() throws Exception {
        if (teleports != null) teleports.shutdown();
        LC.setInstance(previousApi);
        set(Bukkit.class, null, "server", previousServer);
        materials.close();
    }

    @Test void defaultRtpUsesTheCurrentUnconfiguredWorldAndPlayerCenter() {
        config.set("rtp.default-world", "creative");
        config.set("rtp.worlds.creative.max-radius", 5000);
        Player player = players.get("Alice");
        commands.get("rtp").execute(player, "rtp", new String[0]);
        assertEquals(1, destinations.size());
        Location destination = destinations.getFirst();
        assertSame(worlds.get("survival"), destination.getWorld());
        assertTrue(destination.distanceSquared(player.getLocation()) <= 1_000_000d);
    }

    @Test void configuredRadiusIsACircleAroundThePlayer() {
        config.set("rtp.default-radius", 5);
        Player player = players.get("Alice");
        for (int i = 0; i < 20; i++) {
            commands.get("rtp").execute(player, "rtp", new String[0]);
            assertEquals(i + 1, destinations.size());
            assertTrue(destinations.getLast().distanceSquared(player.getLocation()) <= 25);
        }
    }

    @Test void disabledCurrentWorldDoesNotFallBackToAnotherWorld() {
        config.set("rtp.worlds.survival.enabled", false);
        config.set("rtp.worlds.creative.max-radius", 5000);
        commands.get("rtp").execute(players.get("Alice"), "rtp", new String[0]);
        assertTrue(destinations.isEmpty());
        assertTrue(messages.contains("teleport.rtp.world-disabled"));
    }

    @Test void backSearchesTwentyBlocksAndChecksYBeforeTeleporting() {
        var terrain = new RespawnTestWorld("death").ground(null).set(20, 40, 0, Material.STONE);
        worlds.put("death", terrain.world);
        Player player = players.get("Alice");
        config.set("teleport.safe-search-radius", 3);
        config.set("back.safe-search-radius", 3);
        teleports.setBack(player.getUniqueId(), new Location(terrain.world, .5, -80, .5));
        commands.get("back").execute(player, "back", new String[0]);
        assertEquals(List.of(new Location(terrain.world, 20.5, 41, .5)), destinations);
    }

    @Test void safeBackLocationIsNotMoved() {
        var terrain = new RespawnTestWorld("death");
        worlds.put("death", terrain.world);
        Player player = players.get("Alice");
        var death = new Location(terrain.world, .4, 80, .6, 50, 20);
        teleports.setBack(player.getUniqueId(), death);
        commands.get("back").execute(player, "back", new String[0]);
        assertEquals(List.of(death), destinations);
    }
    private World world(String name) {
        UUID id = UUID.randomUUID();
        var border = proxy(WorldBorder.class, (method, args) -> method.equals("isInside") ? true : null);
        return proxy(World.class, (method, args) -> switch (method) {
            case "getUID" -> id;
            case "getName" -> name;
            case "getEnvironment" -> World.Environment.NORMAL;
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getHighestBlockYAt" -> 72;
            case "getWorldBorder" -> border;
            case "getChunkAtAsync" -> { chunkLoads++; yield CompletableFuture.completedFuture(null); }
            case "getBlockAt" -> {
                boolean ground = (int) args[1] <= 72;
                yield proxy(Block.class, (n, a) -> switch (n) {
                    case "getType" -> ground ? Material.STONE : Material.AIR;
                    case "isPassable" -> !ground;
                    default -> null;
                });
            }
            default -> null;
        });
    }

    private Player player(String name, boolean online) {
        UUID id = UUID.randomUUID();
        return proxy(Player.class, (method, args) -> switch (method) {
            case "getUniqueId" -> id;
            case "getName" -> name;
            case "getWorld" -> worlds.get("survival");
            case "isOnline", "canSee" -> online;
            case "getGameMode" -> GameMode.SURVIVAL;
            case "getLocation" -> new Location(worlds.get("survival"), 12000.5, 73, -8000.5);
            case "teleportAsync" -> {
                destinations.add(((Location) args[0]).clone());
                yield CompletableFuture.completedFuture(true);
            }
            case "teleport" -> throw new AssertionError("Paper RTP should use teleportAsync");
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

