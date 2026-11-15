package com.lunarboreal.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 独立头顶称号；不修改原生姓名或占用玩家乘客位置。 */
final class NameplateTitles {
    record Settings(boolean enabled, double height, double range, long ticks) {
        static Settings load(ConfigurationSection config) {
            return new Settings(config.getBoolean("nameplate.enabled", false),
                    finite(config.getDouble("nameplate.height", 2.65), 2.65, 2.65, 8),
                    finite(config.getDouble("nameplate.view-range", 32), 32, 4, 64),
                    Math.max(1, Math.min(20, config.getLong("nameplate.refresh-ticks", 2))));
        }

        private static double finite(double value, double fallback, double min, double max) {
            return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
        }
    }

    private static final class Plate {
        final TextDisplay entity;
        final Set<UUID> viewers = new HashSet<>();
        Component text;
        Location location;

        Plate(TextDisplay entity, Component text, Location location) {
            this.entity = entity;
            this.text = text;
            this.location = location;
        }
    }

    private final ChatModule module;
    private final Map<UUID, Plate> plates = new HashMap<>();
    private BukkitTask task;
    private Settings settings;

    NameplateTitles(ChatModule module) { this.module = module; }

    void restart() {
        stop();
        settings = Settings.load(module.config());
        if (!settings.enabled()) return;
        refresh();
        task = module.runTaskTimer(this::refresh, settings.ticks(), settings.ticks());
    }

    void stop() {
        if (task != null) { task.cancel(); task = null; }
        plates.values().forEach(plate -> plate.entity.remove());
        plates.clear();
    }

    void forget(UUID player) {
        Plate plate = plates.remove(player);
        if (plate != null) plate.entity.remove();
    }

    void quit(UUID player) {
        forget(player);
        plates.values().forEach(other -> other.viewers.remove(player));
    }

    static boolean canDisplay(Player player, Component title, boolean vanished) {
        return player.isOnline() && !player.isDead() && !player.isSneaking()
                && !player.isInvisible() && !vanished && player.getGameMode() != GameMode.SPECTATOR
                && !ChatFormatter.plain(title).isBlank();
    }

    static double height(double configured, double playerHeight) {
        return Math.max(configured, playerHeight + 0.85);
    }

    private void refresh() {
        var online = Bukkit.getOnlinePlayers();
        Set<UUID> present = new HashSet<>();
        for (Player player : online) {
            UUID id = player.getUniqueId();
            present.add(id);
            Component title = module.formatter.appearance.cachedTitle(player);
            if (!canDisplay(player, title, module.isVanished(player))) {
                forget(id);
                continue;
            }
            Location location = player.getLocation().add(0, height(settings.height(), player.getHeight()), 0);
            location.setYaw(0);
            location.setPitch(0);
            Plate plate = plates.get(id);
            if (plate != null && (!plate.entity.isValid() || !plate.entity.getWorld().equals(player.getWorld()))) {
                forget(id);
                plate = null;
            }
            if (plate == null) {
                TextDisplay entity = player.getWorld().spawn(location, TextDisplay.class, display -> {
                    display.setPersistent(false);
                    display.setVisibleByDefault(false);
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setDefaultBackground(false);
                    display.setBackgroundColor(Color.fromARGB(0));
                    display.setShadowed(true);
                    display.setSeeThrough(false);
                    display.setViewRange((float) (settings.range() / 64));
                    display.setTeleportDuration((int) settings.ticks());
                    display.text(title);
                });
                plate = new Plate(entity, title, location);
                plates.put(id, plate);
            } else {
                if (!plate.text.equals(title)) {
                    plate.entity.text(title);
                    plate.text = title;
                }
                if (plate.location.distanceSquared(location) > 0.000001) {
                    plate.entity.teleport(location);
                    plate.location = location;
                }
            }
            Location playerLocation = player.getLocation();
            for (Player viewer : online) {
                UUID viewerId = viewer.getUniqueId();
                boolean visible = !viewerId.equals(id) && viewer.getWorld().equals(player.getWorld())
                        && viewer.getLocation().distanceSquared(playerLocation) <= settings.range() * settings.range()
                        && viewer.canSee(player) && module.canSee(viewer, player);
                if (visible && plate.viewers.add(viewerId)) viewer.showEntity(module.plugin(), plate.entity);
                else if (!visible && plate.viewers.remove(viewerId)) viewer.hideEntity(module.plugin(), plate.entity);
            }
        }
        for (UUID player : Set.copyOf(plates.keySet())) if (!present.contains(player)) forget(player);
    }
}
