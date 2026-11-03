package com.lunarboreal.teleport.command;

import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.teleport.HomeManager;
import com.lunarboreal.teleport.StoredLoc;
import com.lunarboreal.teleport.TeleportManager;
import com.lunarboreal.teleport.TeleportModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** sethome / home / delhome / homes。 */
public final class HomeCommands {

    private static final String OTHERS = "lbr.teleport.home.others";

    private HomeCommands() {}

    /** 解析后的家目标。 */
    private record Target(UUID owner, String ownerName, String home, boolean self) {}

    public static void register(TeleportModule m) {
        HomeManager homes = m.homes();

        m.registerCommand(new SimpleCommand("sethome", "lbr.teleport.sethome", (s, l, a) -> {
            Player p = (Player) s;
            String spec = a.length > 0 ? a[0] : "home";
            resolve(m, s, spec, "lbr.teleport.sethome.others", t -> {
                if (!HomeManager.NAME.matcher(t.home()).matches()) { m.msg(s, "home.invalid-name"); return; }
                if (t.home().equalsIgnoreCase("bed") && m.config().getBoolean("homes.bed-home", true)) {
                    m.msg(s, "home.reserved-name"); return;
                }
                Location loc = p.getLocation();
                if (!m.teleports().landAllowed(p, loc)) { m.msg(s, "home.land-denied"); return; }
                homes.homes(t.owner()).whenComplete((map, err) -> m.runTask(() -> {
                    if (err != null) { m.msg(s, "common.error"); return; }
                    boolean exists = map.containsKey(t.home().toLowerCase(Locale.ROOT));
                    if (t.self() && !exists) {
                        int limit = homes.limit(p);
                        if (limit >= 0 && map.size() >= limit) { m.msg(s, "home.limit", "max", limit); return; }
                    }
                    if (t.self() && !charge(m, p, "sethome")) return;
                    homes.set(t.owner(), t.home(), StoredLoc.of(loc));
                    if (t.self()) m.msg(s, exists ? "home.updated" : "home.set", "name", t.home());
                    else {
                        m.msg(s, "home.set-other", "name", t.home(), "player", t.ownerName());
                        m.audit(p.getName(), "sethome", t.ownerName() + ":" + t.home());
                    }
                }));
            });
        }, "createhome").tab(homeTab(m)).playerOnly(true).description("设置家"));

        m.registerCommand(new SimpleCommand("home", "lbr.teleport.home", (s, l, a) -> {
            Player p = (Player) s;
            if (a.length == 0) {
                homes.homes(p.getUniqueId()).whenComplete((map, err) -> m.runTask(() -> {
                    if (err != null) { m.msg(s, "common.error"); return; }
                    if (map.isEmpty()) {
                        if (!goBed(m, p, true)) m.msg(s, "home.none");
                        return;
                    }
                    HomeManager.Home h = null;
                    if (map.size() == 1) h = map.values().iterator().next();
                    else if (map.containsKey("home")) h = map.get("home");
                    if (h != null) go(m, p, h, true);
                    else list(m, s, p.getName(), map, true);
                }));
                return;
            }
            if (a[0].equalsIgnoreCase("bed") && m.config().getBoolean("homes.bed-home", true)) {
                goBed(m, p, false);
                return;
            }
            resolve(m, s, a[0], OTHERS, t -> homes.homes(t.owner()).whenComplete((map, err) -> m.runTask(() -> {
                if (err != null) { m.msg(s, "common.error"); return; }
                HomeManager.Home h = map.get(t.home().toLowerCase(Locale.ROOT));
                if (h == null) { m.msg(s, "home.not-found", "name", t.home()); return; }
                go(m, p, h, t.self());
            })));
        }).tab(homeTab(m)).playerOnly(true).description("回家"));

        m.registerCommand(new SimpleCommand("delhome", "lbr.teleport.delhome", (s, l, a) -> {
            if (a.length < 1) { m.msg(s, "usage.delhome"); return; }
            resolve(m, s, a[0], "lbr.teleport.delhome.others", t -> homes.homes(t.owner()).whenComplete((map, err) -> m.runTask(() -> {
                if (err != null) { m.msg(s, "common.error"); return; }
                if (!map.containsKey(t.home().toLowerCase(Locale.ROOT))) { m.msg(s, "home.not-found", "name", t.home()); return; }
                homes.delete(t.owner(), t.home());
                if (t.self()) m.msg(s, "home.deleted", "name", t.home());
                else {
                    m.msg(s, "home.deleted-other", "name", t.home(), "player", t.ownerName());
                    m.audit(m.actor(s), "delhome", t.ownerName() + ":" + t.home());
                }
            })));
        }, "remhome", "rmhome").tab(homeTab(m)).description("删除家"));

        m.registerCommand(new SimpleCommand("homes", "lbr.teleport.homes", (s, l, a) -> {
            if (a.length > 0) {
                if (!s.hasPermission(OTHERS)) { m.msg(s, "common.no-permission"); return; }
                resolve(m, s, a[0] + ":x", OTHERS, t -> homes.homes(t.owner()).whenComplete((map, err) -> m.runTask(() -> {
                    if (err != null) { m.msg(s, "common.error"); return; }
                    list(m, s, t.ownerName(), map, t.self());
                })));
                return;
            }
            if (!(s instanceof Player p)) { m.msg(s, "usage.homes"); return; }
            homes.homes(p.getUniqueId()).whenComplete((map, err) -> m.runTask(() -> {
                if (err != null) { m.msg(s, "common.error"); return; }
                list(m, s, p.getName(), map, true);
            }));
        }, "listhomes").tab((s, a) -> a.length == 1 && s.hasPermission(OTHERS) ? SimpleCommand.players(a[0]) : Collections.emptyList())
                .description("列出家"));
    }

    // ---------------------------------------------------------------------

    /** 解析 "名" 或 "玩家:名"（后者需要 othersPerm）。 */
    private static void resolve(TeleportModule m, CommandSender s, String spec, String othersPerm, java.util.function.Consumer<Target> then) {
        int idx = spec.indexOf(':');
        if (idx < 0) {
            if (!(s instanceof Player p)) { m.msg(s, "common.player-only"); return; }
            then.accept(new Target(p.getUniqueId(), p.getName(), spec, true));
            return;
        }
        String ownerName = spec.substring(0, idx);
        String home = spec.substring(idx + 1);
        if (home.isEmpty()) home = "home";
        if (s instanceof Player p && p.getName().equalsIgnoreCase(ownerName)) {
            then.accept(new Target(p.getUniqueId(), p.getName(), home, true));
            return;
        }
        if (!s.hasPermission(othersPerm)) { m.msg(s, "common.no-permission"); return; }
        Player online = Bukkit.getPlayerExact(ownerName);
        if (online != null) {
            then.accept(new Target(online.getUniqueId(), online.getName(), home, false));
            return;
        }
        Optional<UUID> uuid = m.api().players().uuidOf(ownerName);
        if (uuid.isEmpty()) { m.msg(s, "common.player-unknown", "player", ownerName); return; }
        then.accept(new Target(uuid.get(), m.api().players().nameOf(uuid.get()), home, false));
    }

    private static void go(TeleportModule m, Player p, HomeManager.Home h, boolean self) {
        Location loc = h.loc().toLocation();
        if (loc == null) { m.msg(p, "common.world-not-found", "world", h.loc().world()); return; }
        TeleportManager.Request r = m.teleports().request(p, "home").to(loc).success("home.teleported", "name", h.name());
        if (!self) r.charge(false).land(false);
        m.teleports().submit(r);
    }

    /** 床/重生锚家；quiet 为 true 时没有床不提示。 */
    private static boolean goBed(TeleportModule m, Player p, boolean quiet) {
        if (!m.config().getBoolean("homes.bed-home", true) || !p.hasPermission("lbr.teleport.home.bed")) {
            if (!quiet) m.msg(p, "home.not-found", "name", "bed");
            return false;
        }
        Location bed = p.getRespawnLocation();
        if (bed == null) {
            if (!quiet) m.msg(p, "home.no-bed");
            return false;
        }
        m.teleports().submit(m.teleports().request(p, "home").to(bed).success("home.teleported", "name", "bed"));
        return true;
    }

    private static boolean charge(TeleportModule m, Player p, String key) {
        BigDecimal cost = m.teleports().configCost(key);
        if (cost.signum() <= 0 || p.hasPermission(TeleportManager.PERM_BYPASS_COST)) return true;
        Optional<EconomyService> eco = m.economy();
        if (eco.isEmpty()) return true;
        EconomyService.Result r = eco.get().withdraw(p.getUniqueId(), cost, "传送: " + key);
        if (!r.success()) {
            m.msg(p, "teleport.no-money", "cost", eco.get().format(cost));
            return false;
        }
        m.msg(p, "teleport.charged", "cost", eco.get().format(cost));
        return true;
    }

    /** 可点击家列表。 */
    private static void list(TeleportModule m, CommandSender s, String owner, Map<String, HomeManager.Home> map, boolean self) {
        if (map.isEmpty()) {
            m.msg(s, self ? "home.none" : "home.none-other", "player", owner);
            return;
        }
        String max = "";
        if (self && s instanceof Player p) {
            int lim = m.homes().limit(p);
            max = lim < 0 ? "∞" : String.valueOf(lim);
        }
        m.msg(s, self ? "home.list-header" : "home.list-header-other", "player", owner, "count", map.size(), "max", max);
        Component line = Component.empty();
        boolean first = true;
        List<HomeManager.Home> list = new ArrayList<>(map.values());
        for (HomeManager.Home h : list) {
            if (!first) line = line.append(m.api().messages().parse(m.raw("home.list-separator")));
            first = false;
            String cmd = "/home " + (self ? h.name() : owner + ":" + h.name());
            Component entry = m.api().messages().parse(m.raw("home.list-entry", "name", h.name()))
                    .clickEvent(ClickEvent.runCommand(cmd))
                    .hoverEvent(HoverEvent.showText(m.api().messages().parse(
                            m.raw("home.list-hover", "name", h.name(), "location", h.loc().display()))));
            line = line.append(entry);
        }
        s.sendMessage(line);
    }

    private static SimpleCommand.Tab homeTab(TeleportModule m) {
        return (s, a) -> {
            if (a.length != 1 || !(s instanceof Player p)) return Collections.emptyList();
            List<String> names = new ArrayList<>();
            for (HomeManager.Home h : m.homes().cached(p.getUniqueId()).values()) names.add(h.name());
            return SimpleCommand.filterList(names, a[0]);
        };
    }
}
