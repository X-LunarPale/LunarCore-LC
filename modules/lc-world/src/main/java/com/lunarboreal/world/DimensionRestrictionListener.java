package com.lunarboreal.world;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Applies the source world's access rules to portals and other teleports. */
final class DimensionRestrictionListener implements Listener {
    private final WorldModule module;
    DimensionRestrictionListener(WorldModule module) { this.module = module; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(PlayerPortalEvent event) {
        if (blocked(event.getPlayer(), event.getFrom().getWorld(), event.getTo() == null ? null : event.getTo().getWorld()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (blocked(event.getPlayer(), event.getFrom().getWorld(), event.getTo() == null ? null : event.getTo().getWorld()))
            event.setCancelled(true);
    }

    private boolean blocked(Player player, org.bukkit.World from, org.bukkit.World to) {
        if (from == null || to == null || from.getEnvironment() == to.getEnvironment()) return false;
        WorldOptions options = module.worlds.get(from.getName());
        if (WorldOptions.allowsCrossDimension(options, from.getEnvironment(), to.getEnvironment())) return false;
        String destination = switch (to.getEnvironment()) {
            case NORMAL -> "主世界";
            case NETHER -> "下界";
            case THE_END -> "末地";
            default -> "目标维度";
        };
        player.sendMessage("§c当前世界不允许前往§e" + destination + "§c。");
        return true;
    }
}
