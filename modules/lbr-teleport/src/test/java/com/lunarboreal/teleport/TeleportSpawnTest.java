package com.lunarboreal.teleport;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.lunarboreal.api.LBR;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.command.CommandRegistry;
import com.lunarboreal.api.command.LbrCommand;
import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.api.player.PlayerCache;
import com.lunarboreal.teleport.command.SpawnCommands;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the spawn events and command handlers against independent saved player state. */
class TeleportSpawnTest {
    @TempDir Path temp;
    private final TeleportModule module = new TeleportModule();
    private final YamlConfiguration config = new YamlConfiguration();
    private final UUID uuid = UUID.randomUUID();
    private final World survival = world("survival");
    private final World hub = world("hub");
    private final Location saved = new Location(survival, 83, 72, -45);
    private final Location hubSpawn = new Location(hub, 4.5, 80, 8.5);
    private final Map<NamespacedKey, Object> playerData = new HashMap<>();
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final Map<String, LbrCommand> commands = new HashMap<>();
    private long lastJoin;
    private Server oldServer;
    private LunarBorealAPI oldApi;
    private SpawnManager spawns;
    private TeleportListener listener;

    @BeforeEach void setup() throws Exception {
        oldServer = Bukkit.getServer();
        oldApi = LBR.isLoaded() ? LBR.get() : null;
        var scheduler = proxy(BukkitScheduler.class, (name, args) -> {
            if (name.equals("runTaskLater")) {
                tasks.add((Runnable) args[1]);
                return proxy(BukkitTask.class, (n, a) -> null);
            }
            return null;
        });
        set(Bukkit.class, null, "server", proxy(Server.class, (name, args) -> switch (name) {
            case "getScheduler" -> scheduler;
            case "getWorld" -> args[0].equals("hub") ? hub : survival;
            default -> null;
        }));
        var players = proxy(PlayerCache.class, (name, args) -> name.equals("get")
                ? lastJoin <= 0 ? Optional.empty() : Optional.of(new PlayerCache.PlayerRecord(uuid, "Player",
                lastJoin, lastJoin, 0, "", 0)) : null);
        var service = proxy(MessageService.class, (name, args) -> {
            if (name.equals("send")) messages.add((String) args[1]);
            return null;
        });
        var registry = proxy(CommandRegistry.class, (name, args) -> {
            if (name.equals("register")) commands.put(((LbrCommand) args[1]).name(), (LbrCommand) args[1]);
            return null;
        });
        LBR.setInstance(proxy(LunarBorealAPI.class, (name, args) -> switch (name) {
            case "players" -> players;
            case "messages" -> service;
            case "commands" -> registry;
            case "plugin" -> proxy(Plugin.class, (n, a) -> null);
            default -> null;
        }));
        module.init(new ModuleDescription("teleport", "Teleport", "test", "", "", List.of(), List.of(), List.of()),
                temp.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        set(LbrModule.class, module, "config", config);
        set(TeleportModule.class, module, "spawnZone", ZoneId.of("UTC"));
        spawns = new SpawnManager(module);
        set(SpawnManager.class, spawns, "configuredSpawn", hubSpawn);
        set(TeleportModule.class, module, "spawns", spawns);
        listener = new TeleportListener(module);
        SpawnCommands.register(module);
    }

    @AfterEach void cleanup() throws Exception {
        LBR.setInstance(oldApi);
        set(Bukkit.class, null, "server", oldServer);
    }

    @Test void firstLoginOfDaySpawnsAtHubWithoutCallingTeleport() {
        lastJoin = System.currentTimeMillis() - 86_400_000L;
        var event = initial(false);
        listener.onInitialSpawn(event);
        assertEquals(hubSpawn, event.getSpawnLocation());
        assertTrue(tasks.isEmpty());
    }

    @Test void sameDayReconnectAndModuleReloadKeepSavedPosition() {
        lastJoin = System.currentTimeMillis();
        var event = initial(false);
        listener.onInitialSpawn(event);
        assertEquals(saved, event.getSpawnLocation());
        new TeleportListener(module).onInitialSpawn(event);
        assertEquals(saved, event.getSpawnLocation());
    }

    @Test void freshPlayerHonorsFirstJoinSwitchWhenDailyReturnIsOff() {
        config.set("spawn.on-daily-first-join", false);
        var event = initial(true);
        listener.onInitialSpawn(event);
        assertEquals(hubSpawn, event.getSpawnLocation());
        config.set("spawn.on-first-join", false);
        event = initial(true);
        listener.onInitialSpawn(event);
        assertEquals(saved, event.getSpawnLocation());
    }

    @Test void noConfiguredHubLeavesInitialAndDeathLocationsUntouched() throws Exception {
        set(SpawnManager.class, spawns, "configuredSpawn", null);
        var initial = initial(true);
        listener.onInitialSpawn(initial);
        assertEquals(saved, initial.getSpawnLocation());
        var respawn = respawn(player(), false);
        listener.onRespawn(respawn);
        assertEquals(saved, respawn.getRespawnLocation());
        assertTrue(tasks.isEmpty());
    }

    @Test void deathReturnDefaultsOnAndShowsTheHintOnlyForHubRespawns() {
        var respawn = respawn(player(), false);
        listener.onRespawn(respawn);
        assertEquals(hubSpawn, respawn.getRespawnLocation());
        tasks.forEach(Runnable::run);
        assertEquals(List.of("teleport.spawn.death-hint"), messages);
    }

    @Test void changedRespawnDestinationDoesNotShowTheHubHint() {
        var respawn = respawn(player(), false);
        listener.onRespawn(respawn);
        respawn.setRespawnLocation(saved);
        tasks.forEach(Runnable::run);
        assertTrue(messages.isEmpty());
    }

    @Test void ordinaryPlayerCanDisableAndReenableTheirSavedDeathChoice() {
        Player player = player();
        var command = commands.get("spawn");
        assertEquals("lbr.teleport.spawn", command.permission());
        command.execute(player, "spawn", new String[]{"ondeath", "off"});
        assertFalse(SpawnPreferences.returnsOnDeath(player(), true));
        var disabled = respawn(player(), false);
        listener.onRespawn(disabled);
        assertEquals(saved, disabled.getRespawnLocation());
        command.execute(player(), "spawn", new String[]{"ondeath", "on"});
        assertTrue(SpawnPreferences.returnsOnDeath(player(), false));
        assertEquals(List.of("on", "off"), command.tabComplete(player, "spawn", new String[]{"ondeath", ""}));
    }

    @Test void bedAndRespawnMasterSwitchStillApply() {
        var bed = respawn(player(), true);
        listener.onRespawn(bed);
        assertEquals(saved, bed.getRespawnLocation());
        config.set("spawn.on-respawn", false);
        var disabled = respawn(player(), false);
        listener.onRespawn(disabled);
        assertEquals(saved, disabled.getRespawnLocation());
        assertTrue(tasks.isEmpty());
    }

    private AsyncPlayerSpawnLocationEvent initial(boolean fresh) {
        var profile = proxy(PlayerProfile.class, (name, args) -> name.equals("getId") ? uuid : null);
        var connection = proxy(PlayerConfigurationConnection.class, (name, args) -> name.equals("getProfile") ? profile : null);
        return new AsyncPlayerSpawnLocationEvent(connection, saved.clone(), fresh);
    }

    private PlayerRespawnEvent respawn(Player player, boolean bed) {
        return new PlayerRespawnEvent(player, saved.clone(), bed, false, false, PlayerRespawnEvent.RespawnReason.DEATH);
    }

    private Player player() {
        var pdc = proxy(PersistentDataContainer.class, (name, args) -> switch (name) {
            case "get" -> playerData.get(args[0]);
            case "set" -> { playerData.put((NamespacedKey) args[0], args[2]); yield null; }
            default -> null;
        });
        return proxy(Player.class, (name, args) -> switch (name) {
            case "getUniqueId" -> uuid;
            case "isOnline" -> true;
            case "getPersistentDataContainer" -> pdc;
            default -> null;
        });
    }

    private static World world(String name) {
        UUID id = UUID.randomUUID();
        return proxy(World.class, (method, args) -> switch (method) {
            case "getUID" -> id;
            case "getName" -> name;
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
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, args) -> {
            if (m.getName().equals("equals")) return p == args[0];
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("toString")) return type.getSimpleName();
            Object result = answer.answer(m.getName(), args);
            if (result != null || !m.getReturnType().isPrimitive()) return result;
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == long.class) return 0L;
            if (m.getReturnType() == double.class) return 0d;
            if (m.getReturnType() == float.class) return 0f;
            if (m.getReturnType() == void.class) return null;
            return 0;
        });
    }
}
