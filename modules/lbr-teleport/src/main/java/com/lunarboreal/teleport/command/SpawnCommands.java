package com.lunarboreal.teleport.command;

import com.lunarboreal.teleport.TeleportModule;
import com.lunarboreal.teleport.SpawnPreferences;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/** setspawn / spawn / back / rtp。 */
public final class SpawnCommands {

    private SpawnCommands() {}

    public static void register(TeleportModule m) {

        m.registerCommand(new SimpleCommand("setspawn", "lbr.teleport.setspawn", (s, l, a) -> {
            Player p = (Player) s;
            Location loc = p.getLocation();
            m.spawns().set(loc);
            m.msg(s, "spawn.set");
            m.audit(p.getName(), "setspawn", com.lunarboreal.teleport.StoredLoc.of(loc).display());
        }).noTab().playerOnly(true).description("设置服务器出生点"));

        m.registerCommand(new SimpleCommand("spawn", "lbr.teleport.spawn", (s, l, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("ondeath")) {
                if (!(s instanceof Player p)) { m.msg(s, "common.player-only"); return; }
                if (a.length > 2 || a.length == 2 && !a[1].equalsIgnoreCase("on") && !a[1].equalsIgnoreCase("off")) {
                    m.msg(s, "usage.spawn-ondeath");
                    return;
                }
                if (a.length == 2) SpawnPreferences.setReturnsOnDeath(p, a[1].equalsIgnoreCase("on"));
                boolean enabled = SpawnPreferences.returnsOnDeath(p, m.config().getBoolean("spawn.respawn-default-enabled", true));
                m.msg(p, enabled ? "spawn.death-enabled" : "spawn.death-disabled");
                return;
            }
            Location spawn = m.spawns().get();
            if (spawn == null) { m.msg(s, "spawn.unset"); return; }
            if (a.length > 0) {
                if (!s.hasPermission("lbr.teleport.spawn.others")) { m.msg(s, "common.no-permission"); return; }
                Player t = m.findPlayer(s, a[0]);
                if (t == null) return;
                m.teleports().submit(m.teleports().request(t, "spawn").admin().safe(true).to(spawn).success("spawn.teleported"));
                m.msg(s, "spawn.sent-other", "player", t.getName());
                m.audit(m.actor(s), "spawn-other", t.getName());
                return;
            }
            if (!(s instanceof Player p)) { m.msg(s, "usage.spawn"); return; }
            m.teleports().submit(m.teleports().request(p, "spawn").to(spawn).success("spawn.teleported"));
        }).tab((s, a) -> {
            if (a.length == 2 && a[0].equalsIgnoreCase("ondeath"))
                return SimpleCommand.filterList(List.of("on", "off"), a[1]);
            if (a.length == 1) {
                var options = new java.util.ArrayList<>(List.of("ondeath"));
                if (s.hasPermission("lbr.teleport.spawn.others")) options.addAll(SimpleCommand.players(a[0]));
                return SimpleCommand.filterList(options, a[0]);
            }
            return Collections.emptyList();
        }).description("回到出生点或设置死亡回城"));

        m.registerCommand(new SimpleCommand("back", "lbr.teleport.back", (s, l, a) -> {
            Player p = (Player) s;
            Location back = m.teleports().back(p.getUniqueId());
            if (back == null || back.getWorld() == null) { m.msg(s, "back.none"); return; }
            m.teleports().submit(m.teleports().request(p, "back").to(back).success("back.teleported"));
        }, "return").noTab().playerOnly(true).description("回到上一个位置"));

        // /rtp [世界] [玩家]
        m.registerCommand(new SimpleCommand("rtp", "lbr.teleport.rtp", (s, l, a) -> {
            Player target;
            if (a.length > 1) {
                if (!s.hasPermission("lbr.teleport.rtp.others")) { m.msg(s, "common.no-permission"); return; }
                target = m.findPlayer(s, a[1]);
                if (target == null) return;
            } else if (s instanceof Player p) {
                target = p;
            } else { m.msg(s, "usage.rtp"); return; }
            World world;
            List<String> allowed = m.rtp().worlds();
            if (a.length > 0) {
                if (!s.hasPermission("lbr.teleport.rtp.world")) { m.msg(s, "common.no-permission"); return; }
                world = Bukkit.getWorld(a[0]);
                if (world == null) { m.msg(s, "common.world-not-found", "world", a[0]); return; }
            } else {
                world = target.getWorld();
                if (!allowed.contains(world.getName())) {
                    world = Bukkit.getWorld(m.config().getString("rtp.default-world", "world"));
                }
            }
            if (world == null || !allowed.contains(world.getName())) { m.msg(s, "rtp.world-disabled"); return; }
            World fw = world;
            boolean other = !(s instanceof Player sp && sp.getUniqueId().equals(target.getUniqueId()));
            var req = m.teleports().request(target, "rtp")
                    .toAsync(() -> {
                        m.msg(target, "rtp.searching");
                        return m.rtp().find(fw);
                    })
                    .targetFail("rtp.failed")
                    .onSuccess(loc -> m.msg(target, "rtp.success", "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ(), "world", fw.getName()))
                    .success(null);
            if (other) {
                req.admin().safe(true);
                m.msg(s, "rtp.sent-other", "player", target.getName());
                m.audit(m.actor(s), "rtp-other", target.getName() + " @ " + fw.getName());
            }
            m.teleports().submit(req);
        }, "tpr", "wild", "randomtp").tab((s, a) -> {
            if (a.length == 1 && s.hasPermission("lbr.teleport.rtp.world")) return SimpleCommand.filterList(m.rtp().worlds(), a[0]);
            if (a.length == 2 && s.hasPermission("lbr.teleport.rtp.others")) return SimpleCommand.players(a[1]);
            return Collections.emptyList();
        }).description("随机传送"));
    }
}
