package com.lunarboreal.api.service;

import org.bukkit.entity.Player;

/** Core-managed, configurable Minecraft sound feedback for gameplay events. */
public interface SoundService {

    /**
     * Play a configured event sound if it is enabled.
     *
     * @param player player who should hear the sound
     * @param event stable event key, for example {@code teleport-success}
     */
    void play(Player player, String event);
}
