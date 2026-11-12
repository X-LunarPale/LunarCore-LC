package com.lunarboreal.teleport;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.time.ZoneId;

/** Player choices persist with the player save; login dates use the core player record. */
public final class SpawnPreferences {
    private static final NamespacedKey DEATH_SPAWN = new NamespacedKey("lunarcore", "teleport_death_spawn");

    private SpawnPreferences() {}

    public static boolean returnsOnDeath(Player player, boolean defaultEnabled) {
        Byte value = player.getPersistentDataContainer().get(DEATH_SPAWN, PersistentDataType.BYTE);
        return value == null ? defaultEnabled : value != 0;
    }

    public static void setReturnsOnDeath(Player player, boolean enabled) {
        player.getPersistentDataContainer().set(DEATH_SPAWN, PersistentDataType.BYTE, enabled ? (byte) 1 : (byte) 0);
    }

    static boolean firstJoinToday(long lastJoin, long now, ZoneId zone) {
        return lastJoin <= 0 || !Instant.ofEpochMilli(lastJoin).atZone(zone).toLocalDate()
                .equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
    }
}
