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

class RtpNpcCommandTest {
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
    private Object previousStoneType;
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
        // Material#isSolid resolves a server registry in recent Paper APIs.
        Field stoneType = Material.class.getDeclaredField("blockType");
        stoneType.setAccessible(true);
        previousStoneType = stoneType.get(Material.STONE);
        stoneType.set(Material.STONE, (Supplier<BlockType>) () ->
                proxy(BlockType.class, (name, args) -> name.equals("isSolid") ? true : null));
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
            case "getWorld" -> worlds.get(args[0]);
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
        teleports = new TeleportManager(module);
        set(TeleportModule.class, module, "teleports", teleports);
        set(TeleportModule.class, module, "rtp", new RtpManager(module));
        RtpNpcCommand.register(module);
    }

    @AfterEach void cleanup() throws Exception {
        if (teleports != null) teleports.shutdown();
        LC.setInstance(previousApi);
        set(Bukkit.class, null, "server", previousServer);
        set(Material.class, Material.STONE, "blockType", previousStoneType);
    }

    @Test void registersAnAdministrativeCommandUsableByConsole() {
        assertEquals(Set.of("rtpnpc"), commands.keySet());
        assertEquals("lc.teleport.rtp.npc", command().permission());
        assertFalse(command().isPlayerOnly());
    }

    @Test void rejectsMissingAndExtraArgumentsBeforeSearching() {
        reject("usage.rtpnpc");
        reject("usage.rtpnpc", "survival");
        reject("usage.rtpnpc", "survival", "Alice", "1000", "extra");
    }

    @Test void requiresALoadedWorld() {
        reject("common.world-not-found", "missing", "Alice");
    }

    @Test void requiresAnExactOnlinePlayerAndRejectsSelectors() {
        for (String name : List.of("Ali", "Missing", "@p", "Offline"))
            reject("common.player-not-found", "survival", name);
        assertEquals(0, looseLookups, "NPC commands must not fall back to prefix matching");
    }

    @Test void rejectsNonIntegralAndOutOfRangeRadii() {
        for (String radius : List.of("", "0", "-1", "30000001", "2147483648", "1.5", "NaN"))
            reject("rtp.invalid-radius", "survival", "Alice", radius);
    }

    @Test void completesLoadedWorldsOnlineNamesAndSuggestedRadiiWithoutSearching() {
        assertEquals(Set.of("survival", "creative"),
                Set.copyOf(command().tabComplete(sender, "rtpnpc", new String[]{""})));
        assertEquals(List.of("survival"), command().tabComplete(sender, "rtpnpc", new String[]{"sur"}));
        assertEquals(Set.of("Alice", "Bob"),
                Set.copyOf(command().tabComplete(sender, "rtpnpc", new String[]{"survival", ""})));
        assertEquals(List.of("Alice"),
                command().tabComplete(sender, "rtpnpc", new String[]{"survival", "Al"}));
        assertEquals(List.of("1000", "2000", "5000"),
                command().tabComplete(sender, "rtpnpc", new String[]{"survival", "Alice", ""}));
        assertEquals(List.of("2000"),
                command().tabComplete(sender, "rtpnpc", new String[]{"survival", "Alice", "2"}));
        assertTrue(command().tabComplete(sender, "rtpnpc", new String[]{"survival", "Alice", "1000", ""}).isEmpty());
        assertTrue(destinations.isEmpty());
        assertTrue(messages.isEmpty());
        assertEquals(0, chunkLoads);
    }

    @Test void defaultCircleIgnoresConfiguredRtpCenterAndSkipsWarmupAndCharges() {
        config.set("teleport.warmup", 30);
        config.set("costs.rtp", 25);
        config.set("cooldowns.rtp", 120);
        config.set("rtp.worlds.survival.center-x", 10000);
        config.set("rtp.worlds.survival.center-z", -10000);
        config.set("rtp.worlds.survival.max-radius", 2);
        execute("survival", "Alice");
        assertEquals(1, destinations.size());
        Location destination = destinations.get(0);
        assertSame(worlds.get("survival"), destination.getWorld());
        assertTrue(destination.getX() * destination.getX() + destination.getZ() * destination.getZ() <= 1_000_000d);
        assertTrue(messages.contains("teleport.rtp.success"));
        assertEquals(0, scheduledTasks);
        assertEquals(0, economyCalls);
        assertTrue(landChecks > 0);
        assertTrue(teleports.cooldownLeft(players.get("Alice"), "rtp") > 0);
        int loaded = chunkLoads;
        execute("survival", "Alice");
        assertEquals(1, destinations.size(), "NPC clicks must retain the ordinary RTP cooldown");
        assertEquals(loaded, chunkLoads);
        assertTrue(messages.contains("teleport.teleport.cooldown"));
    }

    @Test void acceptsMinimumAndMaximumRadiusForAnyLoadedWorld() {
        for (int radius : new int[]{1, 30_000_000}) {
            execute("creative", "Bob", Integer.toString(radius));
            Location destination = destinations.getLast();
            assertSame(worlds.get("creative"), destination.getWorld());
            assertTrue(destination.getX() * destination.getX() + destination.getZ() * destination.getZ()
                    <= (double) radius * radius);
        }
        assertEquals(2, destinations.size());
    }

    @Test void retainsTheLandPermissionCheck() {
        allowLand = false;
        execute("survival", "Alice", "1");
        assertTrue(destinations.isEmpty());
        assertTrue(landChecks > 0);
        assertTrue(messages.contains("teleport.teleport.land-denied"));
    }

    private void reject(String key, String... args) {
        messages.clear();
        execute(args);
        assertEquals(List.of("teleport." + key), messages, String.join(" ", args));
        assertTrue(destinations.isEmpty());
        assertEquals(0, chunkLoads);
        assertEquals(0, scheduledTasks);
    }

    private void execute(String... args) {
        command().execute(sender, "rtpnpc", args);
    }

    private LcCommand command() {
        return commands.get("rtpnpc");
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
            case "isOnline", "canSee" -> online;
            case "getGameMode" -> GameMode.SURVIVAL;
            case "getLocation" -> new Location(worlds.get("survival"), 50.5, 73, 50.5);
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
