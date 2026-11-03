package com.lunarboreal.essentials.commands;

import com.lunarboreal.api.player.PlayerCache;
import com.lunarboreal.api.util.Durations;
import com.lunarboreal.essentials.Cmd;
import com.lunarboreal.essentials.EssentialsModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 信息查询类命令：seen / whois / list / gc / tps。 */
public final class InfoCommands {

    private InfoCommands() {}

    private static final String[] NONE = new String[0];

    public static void register(EssentialsModule m) {
        m.reg((Cmd) m.cmd("seen", "seen", new String[]{"lastseen"}, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <玩家>");
            Player on = Cmd.findOnline(a[0]);
            PlayerCache cache = m.api().players();
            if (on != null && m.vanish().canSee(s, on)) {
                Optional<PlayerCache.PlayerRecord> r = cache.get(on.getUniqueId());
                long since = r.map(PlayerCache.PlayerRecord::lastJoin).orElse(0L);
                m.msg(s, "seen.online", "player", on.getName(), "time", since > 0 ? Durations.format(System.currentTimeMillis() - since) : "?");
                return;
            }
            Optional<PlayerCache.PlayerRecord> r = cache.getByName(a[0]);
            if (r.isEmpty()) {
                m.msg(s, "seen.never", "player", a[0]);
                return;
            }
            PlayerCache.PlayerRecord rec = r.get();
            long last = Math.max(rec.lastQuit(), rec.lastJoin());
            m.msg(s, "seen.offline", "player", rec.name(), "time", Durations.format(System.currentTimeMillis() - last), "date", date(last));
            if (s.hasPermission("lbr.essentials.seen.ip")) m.msg(s, "seen.ip", "ip", String.valueOf(rec.lastIp()));
        }).usage("<玩家>").description("查看玩家上次在线时间"));

        m.reg((Cmd) m.cmd("whois", "whois", new String[]{"pinfo"}, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <玩家>");
            Player on = Cmd.findOnline(a[0]);
            PlayerCache cache = m.api().players();
            if (on != null && m.vanish().canSee(s, on)) {
                Optional<PlayerCache.PlayerRecord> r = cache.get(on.getUniqueId());
                Location loc = on.getLocation();
                m.msg(s, "whois.header", "player", on.getName());
                m.msg(s, "whois.uuid", "uuid", on.getUniqueId());
                m.msg(s, "whois.health", "health", String.format(Locale.ROOT, "%.1f", on.getHealth()), "food", on.getFoodLevel());
                m.msg(s, "whois.exp", "level", on.getLevel(), "total", on.calculateTotalExperiencePoints());
                m.msg(s, "whois.location", "world", loc.getWorld().getName(), "x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ());
                m.msg(s, "whois.gamemode", "mode", PlayerCommands.modeName(m, on.getGameMode()));
                m.msg(s, "whois.states", "god", m.onOff(m.data(on).god), "fly", m.onOff(on.getAllowFlight()),
                        "afk", m.onOff(m.afk().isAfk(on.getUniqueId())), "vanish", m.onOff(m.vanish().isVanished(on.getUniqueId())),
                        "op", m.onOff(on.isOp()));
                m.msg(s, "whois.ping", "ping", on.getPing());
                if (s.hasPermission("lbr.essentials.whois.ip") && on.getAddress() != null) {
                    m.msg(s, "whois.ip", "ip", on.getAddress().getAddress().getHostAddress());
                }
                r.ifPresent(rec -> m.msg(s, "whois.times", "first", date(rec.firstJoin()), "playtime", Durations.format(rec.playTimeMillis())));
                return;
            }
            Optional<PlayerCache.PlayerRecord> r = cache.getByName(a[0]);
            if (r.isEmpty()) {
                m.msg(s, "seen.never", "player", a[0]);
                return;
            }
            PlayerCache.PlayerRecord rec = r.get();
            m.msg(s, "whois.header", "player", rec.name());
            m.msg(s, "whois.uuid", "uuid", rec.uuid());
            m.msg(s, "whois.offline", "date", date(Math.max(rec.lastQuit(), rec.lastJoin())));
            if (s.hasPermission("lbr.essentials.whois.ip")) m.msg(s, "whois.ip", "ip", String.valueOf(rec.lastIp()));
            m.msg(s, "whois.times", "first", date(rec.firstJoin()), "playtime", Durations.format(rec.playTimeMillis()));
        }).usage("<玩家>").description("查看玩家详细信息"));

        m.reg((Cmd) m.cmd("list", "list", new String[]{"who", "online", "playerlist"}, (s, l, a) -> {
            List<Player> visible = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (m.vanish().canSee(s, p)) visible.add(p);
            m.msg(s, "list.header", "count", visible.size(), "max", Bukkit.getMaxPlayers());
            Map<String, List<String>> groups = new LinkedHashMap<>();
            ConfigurationSection gs = m.config().getConfigurationSection("list.groups");
            List<String> groupOrder = new ArrayList<>();
            if (gs != null) groupOrder.addAll(gs.getKeys(false));
            for (Player p : visible) {
                String group = m.raw("list.default-group");
                if (gs != null) {
                    for (String g : groupOrder) {
                        String perm = gs.getString(g + ".permission");
                        if (perm != null && p.hasPermission(perm)) {
                            group = gs.getString(g + ".name", g);
                            break;
                        }
                    }
                }
                String name = p.getName();
                if (m.afk().isAfk(p.getUniqueId())) name = m.raw("list.afk-tag") + name;
                if (m.vanish().isVanished(p.getUniqueId())) name = m.raw("list.vanish-tag") + name;
                groups.computeIfAbsent(group, k -> new ArrayList<>()).add(name);
            }
            for (var e : groups.entrySet()) {
                m.msg(s, "list.group", "group", com.lunarboreal.api.message.MessageService.rich(e.getKey()), "count", e.getValue().size(), "players", com.lunarboreal.api.message.MessageService.rich(String.join(", ", e.getValue())));
            }
        }).usage("").description("在线玩家列表"));

        m.reg((Cmd) m.cmd("gc", "gc", new String[]{"lag", "mem", "memory", "uptime"}, (s, l, a) -> {
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory() / 1048576L;
            long total = rt.totalMemory() / 1048576L;
            long free = rt.freeMemory() / 1048576L;
            long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            m.msg(s, "gc.uptime", "time", Durations.format(uptime));
            m.msg(s, "gc.tps", "tps", com.lunarboreal.api.message.MessageService.rich(tpsString()));
            m.msg(s, "gc.memory", "used", total - free, "allocated", total, "max", max);
            for (World w : Bukkit.getWorlds()) {
                m.msg(s, "gc.world", "world", w.getName(), "env", w.getEnvironment().name().toLowerCase(Locale.ROOT),
                        "chunks", w.getLoadedChunks().length, "entities", w.getEntityCount(), "players", w.getPlayerCount());
            }
        }).usage("").description("服务器状态（TPS/内存/实体）"));

        m.reg((Cmd) m.cmd("tps", "tps", NONE, (s, l, a) -> {
            m.msg(s, "gc.tps", "tps", com.lunarboreal.api.message.MessageService.rich(tpsString()));
            double mspt = mspt();
            if (mspt >= 0) m.msg(s, "gc.mspt", "mspt", String.format(Locale.ROOT, "%.2f", mspt));
        }).usage("").description("查看 TPS"));
    }

    public static double[] tps() {
        try {
            return Bukkit.getTPS();
        } catch (Throwable t) {
            return null;
        }
    }

    public static double mspt() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable t) {
            return -1;
        }
    }

    static String tpsString() {
        double[] t = tps();
        if (t == null) return "N/A";
        List<String> parts = new ArrayList<>();
        for (double v : t) {
            double c = Math.min(20.0, v);
            String color = c >= 18 ? "green" : c >= 15 ? "yellow" : "red";
            parts.add("<" + color + ">" + String.format(Locale.ROOT, "%.2f", c) + "</" + color + ">");
        }
        return String.join("<gray>, </gray>", parts);
    }

    static String date(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }

    @SuppressWarnings("unused")
    private static void unused(CommandSender s) {}
}
