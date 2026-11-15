package com.lunarboreal.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NameplateTitlesTest {
    @Test void nameplatesAreOptInAndHeightClearsVanillaName() {
        var config = new YamlConfiguration();
        var settings = NameplateTitles.Settings.load(config);
        assertFalse(settings.enabled());
        assertEquals(2.65, NameplateTitles.height(settings.height(), 1.8));
        assertEquals(3.85, NameplateTitles.height(settings.height(), 3));
        assertEquals(32, settings.range());
        assertEquals(2, settings.ticks());
    }

    @Test void invalidSettingsCannotCreateDistantOrPerTickUnboundedDisplays() {
        var config = new YamlConfiguration();
        config.set("nameplate.height", Double.NaN);
        config.set("nameplate.view-range", Double.POSITIVE_INFINITY);
        config.set("nameplate.refresh-ticks", Long.MIN_VALUE);
        var settings = NameplateTitles.Settings.load(config);
        assertEquals(2.65, settings.height());
        assertEquals(32, settings.range());
        assertEquals(1, settings.ticks());
        config.set("nameplate.height", 0);
        config.set("nameplate.view-range", 10000);
        config.set("nameplate.refresh-ticks", Long.MAX_VALUE);
        settings = NameplateTitles.Settings.load(config);
        assertEquals(2.65, settings.height());
        assertEquals(64, settings.range());
        assertEquals(20, settings.ticks());
    }

    @Test void hidesTitlesWithNoTitleOrWhenPlayerIsHidden() {
        Component title = Component.text("[Explorer]");
        assertTrue(NameplateTitles.canDisplay(player(Map.of()), title, false));
        assertFalse(NameplateTitles.canDisplay(player(Map.of()), Component.empty(), false));
        assertFalse(NameplateTitles.canDisplay(player(Map.of()), title, true));
        for (Map<String, Object> state : java.util.List.of(
                Map.<String, Object>of("isOnline", false), Map.<String, Object>of("isDead", true),
                Map.<String, Object>of("isSneaking", true), Map.<String, Object>of("isInvisible", true),
                Map.<String, Object>of("getGameMode", GameMode.SPECTATOR))) {
            assertFalse(NameplateTitles.canDisplay(player(state), title, false), state.toString());
        }
    }

    private static Player player(Map<String, Object> state) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> state.getOrDefault(method.getName(), switch (method.getName()) {
                    case "isOnline" -> true;
                    case "getGameMode" -> GameMode.SURVIVAL;
                    default -> false;
                }));
    }
}
