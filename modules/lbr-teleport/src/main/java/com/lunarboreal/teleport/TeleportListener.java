package com.lunarboreal.teleport;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** 预热取消、死亡点记录、首次加入/重生到出生点、缓存加载与释放。 */
public final class TeleportListener implements Listener {

    private final TeleportModule m;

    public TeleportListener(TeleportModule m) {
        this.m = m;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!m.teleports().hasWarmup(e.getPlayer().getUniqueId())) return;
        m.teleports().checkMove(e.getPlayer(), e.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) m.teleports().onDamage(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        m.teleports().cancelWarmup(p, false);
        if (m.config().getBoolean("back.on-death", true) && p.hasPermission("lbr.teleport.back.ondeath")) {
            m.teleports().setBack(p.getUniqueId(), p.getLocation());
            m.runTaskLater(() -> { if (p.isOnline()) m.msg(p, "back.death-hint"); }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (e.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) return;
        boolean hasBed = e.isBedSpawn() || e.isAnchorSpawn();
        if (hasBed && !m.config().getBoolean("spawn.respawn-ignore-bed", false)) return;
        Player p = e.getPlayer();
        if (m.config().getBoolean("homes.respawn-at-home", false)) {
            HomeManager.Home h = m.homes().cached(p.getUniqueId()).get("home");
            if (h != null) {
                Location l = h.loc().toLocation();
                if (l != null) {
                    e.setRespawnLocation(l);
                    return;
                }
            }
        }
        if (m.config().getBoolean("spawn.on-respawn", true)) {
            Location spawn = m.spawns().get();
            if (spawn != null) e.setRespawnLocation(spawn);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        m.homes().load(p.getUniqueId());
        m.teleports().loadBack(p.getUniqueId());
        if (!p.hasPlayedBefore() && m.config().getBoolean("spawn.on-first-join", true)) {
            m.runTaskLater(() -> {
                if (!p.isOnline()) return;
                Location spawn = m.spawns().get();
                if (spawn != null) {
                    m.teleports().submit(m.teleports().request(p, "spawn").admin().safe(true).recordBack(false)
                            .to(spawn).success(null));
                }
            }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        m.teleports().forget(p.getUniqueId());
        m.tpa().forget(p.getUniqueId());
        m.homes().unload(p.getUniqueId());
        m.teleports().unloadBack(p.getUniqueId());
    }
}
