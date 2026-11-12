package com.lunarboreal.teleport;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
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
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** 预热、死亡点、主城出生与玩家缓存。 */
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
        if (m.config().getBoolean("back.on-death", true) && p.hasPermission("lc.teleport.back.ondeath")) {
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
        if (m.config().getBoolean("spawn.on-respawn", true)
                && SpawnPreferences.returnsOnDeath(p, m.config().getBoolean("spawn.respawn-default-enabled", true))) {
            Location spawn = m.spawns().configured();
            if (spawn != null) {
                e.setRespawnLocation(spawn);
                m.runTaskLater(() -> {
                    if (p.isOnline() && e.getRespawnLocation().equals(spawn)) m.msg(p, "spawn.death-hint");
                }, 1L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInitialSpawn(AsyncPlayerSpawnLocationEvent e) {
        Location spawn = m.spawns().configured();
        if (spawn == null) return;
        var record = m.api().players().get(e.getConnection().getProfile().getId());
        boolean firstJoin = e.isNewPlayer() && m.config().getBoolean("spawn.on-first-join", true);
        long lastJoin = record.map(com.lunarboreal.api.player.PlayerCache.PlayerRecord::lastJoin).orElse(0L);
        boolean daily = m.config().getBoolean("spawn.on-daily-first-join", true)
                && SpawnPreferences.firstJoinToday(lastJoin, System.currentTimeMillis(), m.spawnZone());
        if (firstJoin || daily) e.setSpawnLocation(spawn);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        m.homes().load(p.getUniqueId());
        m.teleports().loadBack(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent e) {
        m.spawns().refresh();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent e) {
        m.spawns().unloaded(e.getWorld());
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
