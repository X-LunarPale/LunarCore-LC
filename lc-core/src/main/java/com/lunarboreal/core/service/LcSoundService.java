package com.lunarboreal.core.service;

import com.lunarboreal.api.service.SoundService;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Configurable sound feedback for common game events. */
public final class LcSoundService implements SoundService {
    private static final Pattern SOUND_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private final Plugin plugin;
    private final Supplier<ConfigurationSection> config;
    private final Set<String> invalidSounds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<String, Long>> lastPlayed = new HashMap<>();

    public LcSoundService(org.bukkit.plugin.java.JavaPlugin plugin) {
        this(plugin, plugin::getConfig);
    }

    LcSoundService(Plugin plugin, Supplier<ConfigurationSection> config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void play(Player player, String event) {
        if (player == null || event == null || event.isBlank() || !plugin.isEnabled()) return;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> play(player, event));
            return;
        }
        ConfigurationSection current = config.get();
        if (!player.isOnline() || !current.getBoolean("sounds.enabled", true)) return;
        ConfigurationSection settings = current.getConfigurationSection("sounds.events." + event);
        if (settings == null || !settings.getBoolean("enabled", true)) return;
        String name = settings.getString("sound", "");
        if (name == null || name.isBlank()) return;
        String sound;
        try {
            sound = soundKey(name.trim());
        } catch (IllegalArgumentException exception) {
            if (invalidSounds.add(name)) plugin.getLogger().warning("无效音效名 sounds.events." + event + ".sound: " + name);
            return;
        }
        float volume = clamp(settings.getDouble("volume", 0.6), 0, 1);
        float pitch = clamp(settings.getDouble("pitch", 1), 0.5, 2);
        long cooldown = Math.max(0, Math.min(60000, settings.getLong("cooldown-ms", 1000)));
        long now = System.currentTimeMillis();
        Map<String, Long> events = lastPlayed.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        Long previous = events.get(event);
        if (previous != null && now - previous < cooldown) return;
        events.put(event, now);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public void forget(UUID player) {
        lastPlayed.remove(player);
    }

    static float clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return (float) min;
        return (float) Math.max(min, Math.min(max, value));
    }

    static String soundKey(String name) {
        if (!name.contains(":") && !name.contains(".") && name.equals(name.toUpperCase(java.util.Locale.ROOT))) {
            return Sound.valueOf(name).getKey().toString();
        }
        String key = name.contains(":") ? name : "minecraft:" + name;
        if (!SOUND_KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid sound key");
        return key;
    }
}
