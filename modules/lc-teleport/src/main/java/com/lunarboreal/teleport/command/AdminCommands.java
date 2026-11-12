package com.lunarboreal.teleport.command;

import com.lunarboreal.teleport.SafeLocation;
import com.lunarboreal.teleport.TeleportModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 管理传送：tp、tphere、tpall、tppos、tpo、tpohere、top、bottom、world。 */
public final class AdminCommands {

    private static final String BYPASS_TOGGLE = "lc.teleport.tptoggle.bypass";

    private AdminCommands() {}

    public static void register(TeleportModule m) {

        // /tp <玩家> | <玩家> <玩家> | <x y z> | <玩家> <x y z>
        m.registerCommand(new SimpleCommand("tp", "lc.teleport.tp", (s, l, a) -> {
            switch (a.length) {
                case 1 -> {
                    if (!(s instanceof Player p)) { m.msg(s, "common.player-only"); return; }
                    Player t = m.findPlayer(s, a[0]);
                    if (t == null || !toggleOk(m, s, t)) return;
                    adminTp(m, s, p, t.getLocation(), "admin.tp-self", "player", t.getName());
                }
                case 2 -> {
                    if (!s.hasPermission("lc.teleport.tp.others")) { m.msg(s, "common.no-permission"); return; }
                    Player from = m.findPlayer(s, a[0]);
                    Player to = from == null ? null : m.findPlayer(s, a[1]);
                    if (to == null || !toggleOk(m, s, to)) return;
                    adminTp(m, s, from, to.getLocation(), "admin.tp-moved", "player", to.getName());
                    m.msg(s, "admin.tp-others", "player", from.getName(), "target", to.getName());
                    m.audit(m.actor(s), "tp", from.getName() + " -> " + to.getName());
                }
                case 3 -> {
                    if (!(s instanceof Player p)) { m.msg(s, "common.player-only"); return; }
                    Location loc = coords(p.getLocation(), a, 0);
                    if (loc == null) { m.msg(s, "common.bad-coords"); return; }
                    adminTp(m, s, p, loc, "admin.tp-coords", "x", fmt(loc.getX()), "y", fmt(loc.getY()), "z", fmt(loc.getZ()));
                }
                case 4 -> {
                    if (!s.hasPermission("lc.teleport.tp.others")) { m.msg(s, "common.no-permission"); return; }
                    Player t = m.findPlayer(s, a[0]);
                    if (t == null) return;
                    Location loc = coords(t.getLocation(), a, 1);
                    if (loc == null) { m.msg(s, "common.bad-coords"); return; }
                    adminTp(m, s, t, loc, "admin.tp-coords", "x", fmt(loc.getX()), "y", fmt(loc.getY()), "z", fmt(loc.getZ()));
                    m.msg(s, "admin.tp-others", "player", t.getName(), "target", fmt(loc.getX()) + " " + fmt(loc.getY()) + " " + fmt(loc.getZ()));
                    m.audit(m.actor(s), "tp", t.getName() + " -> " + loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
                }
                default -> m.msg(s, "usage.tp");
            }
        }, "teleport").tab(coordTab(m)).override(true).description("传送到玩家或坐标"));

        m.registerCommand(new SimpleCommand("tphere", "lc.teleport.tphere", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length < 1) { m.msg(s, "usage.tphere"); return; }
            Player t = m.findPlayer(s, a[0]);
            if (t == null || !toggleOk(m, s, t)) return;
            adminTp(m, s, t, p.getLocation(), "admin.tp-moved", "player", p.getName());
            m.msg(s, "admin.tphere", "player", t.getName());
            m.audit(p.getName(), "tphere", t.getName());
        }, "s").tab(TpaCommands.visible(m)).playerOnly(true).override(true).description("把玩家传送到你身边"));

        m.registerCommand(new SimpleCommand("tpall", "lc.teleport.tpall", (s, l, a) -> {
            Player dest;
            if (a.length > 0) dest = m.findPlayer(s, a[0]);
            else if (s instanceof Player p) dest = p;
            else { m.msg(s, "usage.tpall"); return; }
            if (dest == null) return;
            int n = 0;
            for (Player t : Bukkit.getOnlinePlayers()) {
                if (t == dest) continue;
                adminTp(m, s, t, dest.getLocation(), "admin.tp-moved", "player", dest.getName());
                n++;
            }
            m.msg(s, "admin.tpall", "count", n, "player", dest.getName());
            m.audit(m.actor(s), "tpall", "-> " + dest.getName() + " count=" + n);
        }).tab(TpaCommands.visible(m)).description("把所有玩家传送到你或某玩家处"));

        // /tppos <x> <y> <z> [yaw pitch] [世界]
        m.registerCommand(new SimpleCommand("tppos", "lc.teleport.tppos", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length < 3) { m.msg(s, "usage.tppos"); return; }
            Location base = p.getLocation();
            String worldName = null;
            if (a.length == 4) worldName = a[3];
            if (a.length >= 5) {
                try {
                    base.setYaw(Float.parseFloat(a[3]));
                    base.setPitch(Float.parseFloat(a[4]));
                } catch (NumberFormatException e) { m.msg(s, "common.bad-coords"); return; }
                if (a.length >= 6) worldName = a[5];
            }
            if (worldName != null) {
                World w = Bukkit.getWorld(worldName);
                if (w == null) { m.msg(s, "common.world-not-found", "world", worldName); return; }
                base.setWorld(w);
            }
            Location loc = coords(base, a, 0);
            if (loc == null) { m.msg(s, "common.bad-coords"); return; }
            adminTp(m, s, p, loc, "admin.tp-coords", "x", fmt(loc.getX()), "y", fmt(loc.getY()), "z", fmt(loc.getZ()));
        }).tab((s, a) -> {
            if (a.length == 4 || a.length == 6) {
                List<String> w = new ArrayList<>();
                for (World world : Bukkit.getWorlds()) w.add(world.getName());
                return SimpleCommand.filterList(w, a[a.length - 1]);
            }
            return a.length <= 3 ? List.of("~") : Collections.emptyList();
        }).playerOnly(true).description("传送到坐标"));

        // /tpo <玩家> [玩家2]：无视 tptoggle
        m.registerCommand(new SimpleCommand("tpo", "lc.teleport.tpo", (s, l, a) -> {
            if (a.length == 1) {
                if (!(s instanceof Player p)) { m.msg(s, "common.player-only"); return; }
                Player t = m.findPlayer(s, a[0]);
                if (t == null) return;
                adminTp(m, s, p, t.getLocation(), "admin.tp-self", "player", t.getName());
            } else if (a.length == 2) {
                if (!s.hasPermission("lc.teleport.tpo.others")) { m.msg(s, "common.no-permission"); return; }
                Player from = m.findPlayer(s, a[0]);
                Player to = from == null ? null : m.findPlayer(s, a[1]);
                if (to == null) return;
                adminTp(m, s, from, to.getLocation(), "admin.tp-moved", "player", to.getName());
                m.msg(s, "admin.tp-others", "player", from.getName(), "target", to.getName());
                m.audit(m.actor(s), "tpo", from.getName() + " -> " + to.getName());
            } else m.msg(s, "usage.tpo");
        }).tab(TpaCommands.visible(m)).description("强制传送（无视 tptoggle）"));

        m.registerCommand(new SimpleCommand("tpohere", "lc.teleport.tpohere", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length < 1) { m.msg(s, "usage.tpohere"); return; }
            Player t = m.findPlayer(s, a[0]);
            if (t == null) return;
            adminTp(m, s, t, p.getLocation(), "admin.tp-moved", "player", p.getName());
            m.msg(s, "admin.tphere", "player", t.getName());
            m.audit(p.getName(), "tpohere", t.getName());
        }).tab(TpaCommands.visible(m)).playerOnly(true).description("强制把玩家传送到你身边（无视 tptoggle）"));

        m.registerCommand(new SimpleCommand("top", "lc.teleport.top", (s, l, a) -> {
            Player p = (Player) s;
            Location c = p.getLocation();
            World w = c.getWorld();
            int y = w.getHighestBlockYAt(c.getBlockX(), c.getBlockZ()) + 1;
            if (y <= w.getMinHeight() + 1) { m.msg(s, "teleport.unsafe"); return; }
            Location loc = new Location(w, c.getBlockX() + 0.5, y, c.getBlockZ() + 0.5, c.getYaw(), c.getPitch());
            adminTp(m, s, p, loc, "admin.top");
        }).noTab().playerOnly(true).description("传送到脚下最高方块之上"));

        m.registerCommand(new SimpleCommand("bottom", "lc.teleport.bottom", (s, l, a) -> {
            Player p = (Player) s;
            Location c = p.getLocation();
            World w = c.getWorld();
            for (int y = w.getMinHeight() + 1; y < c.getBlockY(); y++) {
                if (SafeLocation.isSafe(w, c.getBlockX(), y, c.getBlockZ())) {
                    adminTp(m, s, p, new Location(w, c.getBlockX() + 0.5, y, c.getBlockZ() + 0.5, c.getYaw(), c.getPitch()), "admin.bottom");
                    return;
                }
            }
            m.msg(s, "teleport.unsafe");
        }).noTab().playerOnly(true).description("传送到脚下最低的安全位置"));

        m.registerCommand(new SimpleCommand("world", "lc.teleport.world", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length < 1) {
                List<String> names = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) names.add(w.getName());
                m.msg(s, "admin.world-list", "worlds", String.join(", ", names));
                return;
            }
            World w = Bukkit.getWorld(a[0]);
            if (w == null) { m.msg(s, "common.world-not-found", "world", a[0]); return; }
            m.teleports().submit(m.teleports().request(p, "world").admin().safe(true)
                    .to(w.getSpawnLocation().add(0.5, 0, 0.5))
                    .success("admin.world", "world", w.getName()));
        }).tab((s, a) -> {
            if (a.length != 1) return Collections.emptyList();
            List<String> names = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) names.add(w.getName());
            return SimpleCommand.filterList(names, a[0]);
        }).playerOnly(true).description("传送到某世界出生点"));
    }

    private static boolean toggleOk(TeleportModule m, CommandSender s, Player target) {
        if (s instanceof Player p && p.getUniqueId().equals(target.getUniqueId())) return true;
        if (m.tpa().accepts(target.getUniqueId()) || s.hasPermission(BYPASS_TOGGLE)) return true;
        m.msg(s, "tpa.target-disabled", "player", target.getName());
        return false;
    }

    private static void adminTp(TeleportModule m, CommandSender s, Player who, Location to, String successKey, Object... args) {
        m.teleports().submit(m.teleports().request(who, "tp").admin().to(to).success(successKey, args));
    }

    /** 解析 a[off..off+2] 三个坐标，支持 ~ 相对坐标。 */
    static Location coords(Location base, String[] a, int off) {
        if (a.length < off + 3) return null;
        try {
            double x = axis(a[off], base.getX());
            double y = axis(a[off + 1], base.getY());
            double z = axis(a[off + 2], base.getZ());
            if (Math.abs(x) > 3.0E7 || Math.abs(z) > 3.0E7) return null;
            // 整数坐标居中到方块中心（与原版一致）
            if (!a[off].contains(".") && !a[off].startsWith("~")) x += 0.5;
            if (!a[off + 2].contains(".") && !a[off + 2].startsWith("~")) z += 0.5;
            Location l = base.clone();
            l.setX(x);
            l.setY(y);
            l.setZ(z);
            return l;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double axis(String s, double base) {
        if (s.startsWith("~")) {
            String rest = s.substring(1);
            return rest.isEmpty() ? base : base + Double.parseDouble(rest);
        }
        return Double.parseDouble(s);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static SimpleCommand.Tab coordTab(TeleportModule m) {
        SimpleCommand.Tab players = TpaCommands.visible(m);
        return (s, a) -> {
            if (a.length == 1) {
                List<String> r = new ArrayList<>(players.complete(s, a));
                if ("~".startsWith(a[0])) r.add("~");
                return r;
            }
            if (a.length == 2) {
                List<String> r = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (m.canSee(s, p) && p.getName().toLowerCase().startsWith(a[1].toLowerCase())) r.add(p.getName());
                }
                if ("~".startsWith(a[1])) r.add("~");
                return r;
            }
            return a.length <= 4 ? List.of("~") : Collections.emptyList();
        };
    }
}
