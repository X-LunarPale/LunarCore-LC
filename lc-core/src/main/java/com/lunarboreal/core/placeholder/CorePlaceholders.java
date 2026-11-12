package com.lunarboreal.core.placeholder;

import com.lunarboreal.api.placeholder.PlaceholderRegistry;
import com.lunarboreal.api.player.PlayerCache;
import com.lunarboreal.api.util.Durations;
import com.lunarboreal.core.LunarCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;

/**
 * core 内置变量（%lc_core_xxx%）：
 * online, max_players, tps, tps_5m, tps_15m, mspt, version, modules, players_total,
 * player_name, player_uuid, player_world, player_ping, playtime, playtime_ms, playtime_hours,
 * first_join, first_join_ms, last_join, last_quit, is_online
 */
public final class CorePlaceholders implements PlaceholderRegistry.Resolver {

    private final LunarCorePlugin plugin;

    public CorePlaceholders(LunarCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static String date(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms));
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", Math.min(20.0, d));
    }

    @Override
    public String resolve(OfflinePlayer player, String key) {
        String k = key.toLowerCase(Locale.ROOT);
        switch (k) {
            case "online": return String.valueOf(Bukkit.getOnlinePlayers().size());
            case "max_players": return String.valueOf(Bukkit.getMaxPlayers());
            case "tps": return fmt(plugin.tps()[0]);
            case "tps_5m": return fmt(plugin.tps()[1]);
            case "tps_15m": return fmt(plugin.tps()[2]);
            case "mspt": return String.format(Locale.ROOT, "%.2f", plugin.mspt());
            case "version": return plugin.version();
            case "modules": return String.valueOf(plugin.moduleManager().loaded().size());
            case "players_total": return String.valueOf(plugin.playerCache().size());
            default: break;
        }
        if (player == null) return null;
        Optional<PlayerCache.PlayerRecord> rec = plugin.playerCache().get(player.getUniqueId());
        Player online = player.getPlayer();
        return switch (k) {
            case "player_name" -> online != null ? online.getName() : rec.map(PlayerCache.PlayerRecord::name).orElse(player.getName());
            case "player_uuid" -> player.getUniqueId().toString();
            case "player_world" -> online != null ? online.getWorld().getName() : "";
            case "player_ping" -> online != null ? String.valueOf(online.getPing()) : "0";
            case "is_online" -> String.valueOf(online != null);
            case "playtime" -> Durations.format(rec.map(PlayerCache.PlayerRecord::playTimeMillis).orElse(0L));
            case "playtime_ms" -> String.valueOf(rec.map(PlayerCache.PlayerRecord::playTimeMillis).orElse(0L));
            case "playtime_hours" -> String.format(Locale.ROOT, "%.1f", rec.map(PlayerCache.PlayerRecord::playTimeMillis).orElse(0L) / 3_600_000.0);
            case "first_join" -> date(rec.map(PlayerCache.PlayerRecord::firstJoin).orElse(0L));
            case "first_join_ms" -> String.valueOf(rec.map(PlayerCache.PlayerRecord::firstJoin).orElse(0L));
            case "last_join" -> date(rec.map(PlayerCache.PlayerRecord::lastJoin).orElse(0L));
            case "last_quit" -> date(rec.map(PlayerCache.PlayerRecord::lastQuit).orElse(0L));
            default -> null;
        };
    }
}
