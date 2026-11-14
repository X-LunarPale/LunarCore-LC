package com.lunarboreal.core.service;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("Temporarily replaces Bukkit's server")
class LcSoundServiceTest {
    private final YamlConfiguration config = new YamlConfiguration();
    private final List<Object[]> played = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final UUID uuid = UUID.randomUUID();
    private Server previousServer;
    private boolean primary = true;
    private boolean online = true;
    private int onlineReads;
    private Player player;
    private LcSoundService sounds;

    @BeforeEach
    void setup() throws Exception {
        previousServer = Bukkit.getServer();
        var scheduler = proxy(BukkitScheduler.class, (method, args) -> {
            if (method.equals("runTask")) scheduled.add((Runnable) args[1]);
            return null;
        });
        setServer(proxy(Server.class, (method, args) -> switch (method) {
            case "isPrimaryThread" -> primary;
            case "getScheduler" -> scheduler;
            default -> null;
        }));
        var plugin = proxy(Plugin.class, (method, args) -> switch (method) {
            case "isEnabled" -> true;
            case "getLogger" -> Logger.getAnonymousLogger();
            default -> null;
        });
        player = proxy(Player.class, (method, args) -> switch (method) {
            case "isOnline" -> { onlineReads++; yield online; }
            case "getUniqueId" -> uuid;
            case "playSound" -> { played.add(args); yield null; }
            default -> null;
        });
        config.set("sounds.events.teleport-success.sound", "minecraft:entity.experience_orb.pickup");
        sounds = new LcSoundService(plugin, () -> config);
    }

    @AfterEach
    void cleanup() throws Exception {
        setServer(previousServer);
    }

    @Test
    void playsConfiguredSoundAndClampsVolumeAndPitch() {
        config.set("sounds.events.teleport-success.volume", 12);
        config.set("sounds.events.teleport-success.pitch", -3);
        sounds.play(player, "teleport-success");
        assertEquals(1, played.size());
        assertEquals("minecraft:entity.experience_orb.pickup", played.getFirst()[1]);
        assertEquals(1f, played.getFirst()[2]);
        assertEquals(0.5f, played.getFirst()[3]);
    }

    @Test
    void suppressesRepeatedEventsAndClearsStateOnQuit() {
        sounds.play(player, "teleport-success");
        sounds.play(player, "teleport-success");
        assertEquals(1, played.size());
        sounds.forget(uuid);
        sounds.play(player, "teleport-success");
        assertEquals(2, played.size());
    }

    @Test
    void disablingEventOrAllSoundsAndOfflinePlayersProducesNoPackets() {
        config.set("sounds.events.teleport-success.enabled", false);
        sounds.play(player, "teleport-success");
        config.set("sounds.events.teleport-success.enabled", true);
        config.set("sounds.enabled", false);
        sounds.play(player, "teleport-success");
        config.set("sounds.enabled", true);
        online = false;
        sounds.play(player, "teleport-success");
        assertTrue(played.isEmpty());
    }

    @Test
    void asynchronousCallsTouchPlayerStateOnlyOnMainThread() {
        primary = false;
        sounds.play(player, "teleport-success");
        assertEquals(0, onlineReads);
        assertEquals(1, scheduled.size());
        assertTrue(played.isEmpty());
        primary = true;
        scheduled.getFirst().run();
        assertEquals(1, played.size());
    }

    @Test
    void configReloadTakesEffectAndCooldownCanBeDisabled() {
        config.set("sounds.events.teleport-success.cooldown-ms", 0);
        sounds.play(player, "teleport-success");
        config.set("sounds.events.teleport-success.sound", "block.note_block.pling");
        sounds.play(player, "teleport-success");
        assertEquals(2, played.size());
        assertEquals("minecraft:block.note_block.pling", played.getLast()[1]);
    }

    @Test
    void malformedNamesAndNonFiniteNumbersAreHandled() {
        assertThrows(IllegalArgumentException.class, () -> LcSoundService.soundKey("minecraft:bad sound"));
        assertThrows(IllegalArgumentException.class, () -> LcSoundService.soundKey("minecraft:../sound?"));
        assertEquals("custom:ui/chime", LcSoundService.soundKey("custom:ui/chime"));
        assertEquals(0f, LcSoundService.clamp(Double.NaN, 0, 1));
        assertEquals(0.5f, LcSoundService.clamp(Double.POSITIVE_INFINITY, 0.5, 2));
    }

    private static void setServer(Server server) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }

    @FunctionalInterface
    private interface Answer { Object get(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
            if (method.getName().equals("equals")) return instance == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
            if (method.getName().equals("toString")) return type.getSimpleName();
            Object value = answer.get(method.getName(), args);
            if (value != null || !method.getReturnType().isPrimitive()) return value;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == void.class) return null;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == double.class) return 0d;
            if (method.getReturnType() == float.class) return 0f;
            return 0;
        });
    }
}
