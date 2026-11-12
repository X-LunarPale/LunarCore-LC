package com.lunarboreal.world;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Prevents cross-environment travel when the source world opts out. */
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
        player.sendMessage("§c当前世界禁止进入其他维度。");
        return true;
    }
}
