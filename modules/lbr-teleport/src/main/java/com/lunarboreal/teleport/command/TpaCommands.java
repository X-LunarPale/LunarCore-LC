package com.lunarboreal.teleport.command;

import com.lunarboreal.teleport.TeleportModule;
import com.lunarboreal.teleport.TpaManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** tpa / tpahere / tpaccept / tpdeny / tpacancel / tptoggle / tpaall。 */
public final class TpaCommands {

    private TpaCommands() {}

    public static void register(TeleportModule m) {
        TpaManager tpa = m.tpa();

        m.registerCommand(new SimpleCommand("tpa", "lbr.teleport.tpa", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length < 1) { m.msg(s, "usage.tpa"); return; }
            Player t = m.findPlayer(s, a[0]);
            if (t != null) tpa.send(p, t, false);
        }, "call", "tpask").tab(visible(m)).playerOnly(true).description("请求传送到某玩家"));

        m.registerCommand(new SimpleCommand("tpahere", "lbr.teleport.tpahere", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length < 1) { m.msg(s, "usage.tpahere"); return; }
            Player t = m.findPlayer(s, a[0]);
            if (t != null) tpa.send(p, t, true);
        }).tab(visible(m)).playerOnly(true).description("请求某玩家传送到你身边"));

        m.registerCommand(new SimpleCommand("tpaall", "lbr.teleport.tpaall", (s, l, a) -> {
            Player p = (Player) s;
            int n = 0;
            for (Player t : Bukkit.getOnlinePlayers()) {
                if (t == p || !tpa.accepts(t.getUniqueId())) continue;
                if (tpa.send(p, t, true)) n++;
            }
            m.msg(s, "tpa.all-sent", "count", n);
            m.audit(p.getName(), "tpaall", "count=" + n);
        }).noTab().playerOnly(true).description("向所有玩家发送 tpahere 请求"));

        m.registerCommand(new SimpleCommand("tpaccept", "lbr.teleport.tpaccept", (s, l, a) -> {
            Player p = (Player) s;
            TpaManager.TpaRequest r = tpa.find(p.getUniqueId(), a.length > 0 ? a[0] : null);
            if (r == null) { m.msg(s, "tpa.none"); return; }
            tpa.accept(p, r);
        }, "tpyes").tab(requesters(m)).playerOnly(true).description("接受传送请求"));

        m.registerCommand(new SimpleCommand("tpdeny", "lbr.teleport.tpdeny", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length > 0 && a[0].equals("*")) {
                List<TpaManager.TpaRequest> all = tpa.all(p.getUniqueId());
                if (all.isEmpty()) { m.msg(s, "tpa.none"); return; }
                for (TpaManager.TpaRequest r : all) tpa.deny(p, r);
                return;
            }
            TpaManager.TpaRequest r = tpa.find(p.getUniqueId(), a.length > 0 ? a[0] : null);
            if (r == null) { m.msg(s, "tpa.none"); return; }
            tpa.deny(p, r);
        }, "tpno").tab(requesters(m)).playerOnly(true).description("拒绝传送请求"));

        m.registerCommand(new SimpleCommand("tpacancel", "lbr.teleport.tpacancel", (s, l, a) -> {
            Player p = (Player) s;
            int n = tpa.cancel(p, a.length > 0 ? a[0] : null);
            if (n == 0) m.msg(s, "tpa.none-outgoing");
            else m.msg(s, "tpa.cancelled", "count", n);
        }).tab(visible(m)).playerOnly(true).description("取消你发出的传送请求"));

        m.registerCommand(new SimpleCommand("tptoggle", "lbr.teleport.tptoggle", (s, l, a) -> {
            Player p = (Player) s;
            boolean on = tpa.toggle(p.getUniqueId());
            m.msg(s, on ? "tpa.toggle-on" : "tpa.toggle-off");
        }).noTab().playerOnly(true).description("开关是否接收传送请求"));
    }

    static SimpleCommand.Tab visible(TeleportModule m) {
        return (s, a) -> {
            if (a.length != 1) return Collections.emptyList();
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (m.canSee(s, p) && p.getName().toLowerCase().startsWith(a[0].toLowerCase())) out.add(p.getName());
            }
            return out;
        };
    }

    private static SimpleCommand.Tab requesters(TeleportModule m) {
        return (CommandSender s, String[] a) -> {
            if (a.length != 1 || !(s instanceof Player p)) return Collections.emptyList();
            List<String> names = new ArrayList<>();
            for (TpaManager.TpaRequest r : m.tpa().all(p.getUniqueId())) names.add(r.requesterName());
            return SimpleCommand.filterList(names, a[0]);
        };
    }
}
