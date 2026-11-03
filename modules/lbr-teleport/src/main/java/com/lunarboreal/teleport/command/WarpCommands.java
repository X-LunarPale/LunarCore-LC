package com.lunarboreal.teleport.command;

import com.lunarboreal.teleport.HomeManager;
import com.lunarboreal.teleport.StoredLoc;
import com.lunarboreal.teleport.TeleportManager;
import com.lunarboreal.teleport.TeleportModule;
import com.lunarboreal.teleport.WarpGui;
import com.lunarboreal.teleport.WarpManager;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** setwarp / delwarp / warp / warps。 */
public final class WarpCommands {

    private WarpCommands() {}

    public static void register(TeleportModule m) {
        WarpManager warps = m.warps();

        m.registerCommand(new SimpleCommand("setwarp", "lbr.teleport.setwarp", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length < 1) { m.msg(s, "usage.setwarp"); return; }
            String name = a[0];
            if (!HomeManager.NAME.matcher(name).matches()) { m.msg(s, "warp.invalid-name"); return; }
            WarpManager.Warp old = warps.get(name);
            if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) { m.msg(s, "warp.reserved-name"); return; }
            WarpManager.Warp w = old != null
                    ? old.withLoc(StoredLoc.of(p.getLocation()), p.getName(), System.currentTimeMillis())
                    : new WarpManager.Warp(name, StoredLoc.of(p.getLocation()), false, 0, p.getName(),
                    System.currentTimeMillis(), null, null);
            warps.save(w).exceptionally(e -> { m.logger().warning("保存地标失败: " + e.getMessage()); return 0; });
            m.msg(s, old != null ? "warp.updated" : "warp.set", "name", w.name());
            m.audit(p.getName(), "setwarp", w.name() + " @ " + w.loc().display());
        }, "createwarp").tab(warpTab(m, false)).playerOnly(true).description("设置地标"));

        m.registerCommand(new SimpleCommand("delwarp", "lbr.teleport.delwarp", (s, l, a) -> {
            if (a.length < 1) { m.msg(s, "usage.delwarp"); return; }
            WarpManager.Warp w = warps.get(a[0]);
            if (w == null) { m.msg(s, "warp.not-found", "name", a[0]); return; }
            warps.delete(w.name());
            m.msg(s, "warp.deleted", "name", w.name());
            m.audit(m.actor(s), "delwarp", w.name());
        }, "remwarp", "rmwarp").tab(warpTab(m, false)).description("删除地标"));

        m.registerCommand(new SimpleCommand("warp", "lbr.teleport.warp", (s, l, a) -> {
            if (a.length < 1) { openOrList(m, s); return; }
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("list")) { list(m, s); return; }
            if (sub.equals("seticon") || sub.equals("setdesc")) {
                if (!s.hasPermission("lbr.teleport.setwarp")) { m.msg(s, "common.no-permission"); return; }
                if (a.length < 2) { m.msg(s, sub.equals("seticon") ? "usage.warp-seticon" : "usage.warp-setdesc"); return; }
                WarpManager.Warp w = warps.get(a[1]);
                if (w == null) { m.msg(s, "warp.not-found", "name", a[1]); return; }
                if (sub.equals("seticon")) {
                    String icon;
                    if (a.length >= 3) {
                        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(a[2]);
                        if (mat == null || !mat.isItem() || mat.isAir()) { m.msg(s, "warp.bad-icon", "icon", a[2]); return; }
                        icon = mat.name();
                    } else {
                        if (!(s instanceof Player p)) { m.msg(s, "usage.warp-seticon"); return; }
                        ItemStack hand = p.getInventory().getItemInMainHand();
                        if (hand.getType().isAir()) { m.msg(s, "warp.hold-item"); return; }
                        icon = hand.getType().name();
                    }
                    warps.save(w.withIcon(icon));
                    m.msg(s, "warp.icon-set", "name", w.name(), "icon", icon);
                    m.audit(m.actor(s), "warp-seticon", w.name() + " = " + icon);
                } else {
                    String desc = a.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : null;
                    if (desc != null && desc.length() > 255) desc = desc.substring(0, 255);
                    warps.save(w.withDescription(desc));
                    m.msg(s, desc == null ? "warp.desc-cleared" : "warp.desc-set", "name", w.name());
                    m.audit(m.actor(s), "warp-setdesc", w.name() + " = " + desc);
                }
                return;
            }
            WarpManager.Warp w = warps.get(a[0]);
            if (w == null || !warps.canUse(s, w)) { m.msg(s, "warp.not-found", "name", a[0]); return; }
            Location loc = w.loc().toLocation();
            if (loc == null) { m.msg(s, "common.world-not-found", "world", w.loc().world()); return; }
            Player target;
            boolean other = false;
            if (a.length > 1) {
                if (!s.hasPermission("lbr.teleport.warp.others")) { m.msg(s, "common.no-permission"); return; }
                target = m.findPlayer(s, a[1]);
                if (target == null) return;
                other = !(s instanceof Player sp && sp.getUniqueId().equals(target.getUniqueId()));
            } else if (s instanceof Player p) {
                target = p;
            } else { m.msg(s, "usage.warp"); return; }
            TeleportManager.Request r = m.teleports().request(target, "warp").to(loc).success("warp.teleported", "name", w.name());
            if (w.cost() > 0) r.cost(BigDecimal.valueOf(w.cost()));
            if (other) {
                r.admin().safe(true);
                m.msg(s, "warp.sent-other", "name", w.name(), "player", target.getName());
                m.audit(m.actor(s), "warp-other", target.getName() + " -> " + w.name());
            }
            m.teleports().submit(r);
        }).tab((s, a) -> {
            boolean admin = s.hasPermission("lbr.teleport.setwarp");
            if (a.length == 1) {
                List<String> r = new ArrayList<>(warpTab(m, true).complete(s, a));
                List<String> subs = new ArrayList<>(List.of("list"));
                if (admin) { subs.add("seticon"); subs.add("setdesc"); }
                r.addAll(SimpleCommand.filterList(subs, a[0]));
                return r;
            }
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (admin && (sub.equals("seticon") || sub.equals("setdesc"))) {
                if (a.length == 2) return warpTab(m, false).complete(s, new String[]{a[1]});
                if (a.length == 3 && sub.equals("seticon")) {
                    List<String> mats = new ArrayList<>();
                    String pre = a[2].toUpperCase(Locale.ROOT);
                    for (org.bukkit.Material mat : org.bukkit.Material.values()) {
                        if (mat.isItem() && !mat.isLegacy() && mat.name().startsWith(pre)) mats.add(mat.name());
                        if (mats.size() >= 50) break;
                    }
                    return mats;
                }
                return Collections.emptyList();
            }
            if (a.length == 2 && s.hasPermission("lbr.teleport.warp.others")) return SimpleCommand.players(a[1]);
            return Collections.emptyList();
        }).description("传送到地标"));

        m.registerCommand(new SimpleCommand("warps", "lbr.teleport.warps", (s, l, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("list")) list(m, s);
            else openOrList(m, s);
        }, "listwarps").tab((s, a) -> a.length == 1 ? SimpleCommand.filterList(List.of("list"), a[0]) : Collections.emptyList())
                .description("打开地标 GUI（/warps list 为文本列表）"));
    }

    /** 子命令名，不能用作地标名。 */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("list", "seticon", "setdesc");

    /** 玩家且启用 GUI 时打开箱子菜单，否则文本列表。 */
    private static void openOrList(TeleportModule m, CommandSender s) {
        if (s instanceof Player p && m.config().getBoolean("warps.gui.enabled", true)) {
            if (!WarpGui.open(m, p, 0)) m.msg(s, "warp.none");
            return;
        }
        list(m, s);
    }

    private static void list(TeleportModule m, CommandSender s) {
        List<WarpManager.Warp> usable = new ArrayList<>();
        for (WarpManager.Warp w : m.warps().all()) if (m.warps().canUse(s, w)) usable.add(w);
        if (usable.isEmpty()) { m.msg(s, "warp.none"); return; }
        m.msg(s, "warp.list-header", "count", usable.size());
        Component line = Component.empty();
        boolean first = true;
        for (WarpManager.Warp w : usable) {
            if (!first) line = line.append(m.api().messages().parse(m.raw("warp.list-separator")));
            first = false;
            String cost = "";
            if (w.cost() > 0 && m.economy().isPresent()) cost = m.economy().get().format(BigDecimal.valueOf(w.cost()));
            Component entry = m.api().messages().parse(m.raw("warp.list-entry", "name", w.name()))
                    .clickEvent(ClickEvent.runCommand("/warp " + w.name()))
                    .hoverEvent(HoverEvent.showText(m.api().messages().parse(
                            m.raw(cost.isEmpty() ? "warp.list-hover" : "warp.list-hover-cost",
                                    "name", w.name(), "location", w.loc().display(), "cost", cost))));
            line = line.append(entry);
        }
        s.sendMessage(line);
    }

    private static SimpleCommand.Tab warpTab(TeleportModule m, boolean onlyUsable) {
        return (s, a) -> {
            if (a.length != 1) return Collections.emptyList();
            List<String> names = new ArrayList<>();
            for (WarpManager.Warp w : m.warps().all()) {
                if (!onlyUsable || m.warps().canUse(s, w)) names.add(w.name());
            }
            return SimpleCommand.filterList(names, a[0]);
        };
    }
}
