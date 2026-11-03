package com.lunarboreal.antixray;

import com.lunarboreal.api.command.LbrCommand;
import com.lunarboreal.api.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** /antixray（别名 /ax、/xray）。 */
final class AntiXrayCommand extends LbrCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final List<String> SUBS = List.of("stats", "alerts", "top", "paper", "fakeore", "reload");

    private final AntiXrayModule m;

    AntiXrayCommand(AntiXrayModule m) {
        super("antixray", "lbr.antixray.admin", "ax", "xray");
        this.m = m;
        description(m.raw("command-desc"));
    }

    private static String actor(CommandSender s) {
        return s instanceof ConsoleCommandSender ? "Console" : s.getName();
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) { m.msg(sender, "help"); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stats" -> stats(sender, args);
            case "alerts" -> alerts(sender, args);
            case "top" -> top(sender);
            case "paper" -> paper(sender, args);
            case "fakeore" -> m.msg(sender, "fakeore.status",
                    "enabled", MessageService.rich(m.fakeOre().isEnabled() ? m.raw("words.on") : m.raw("words.off")),
                    "chunks", m.fakeOre().cachedChunks(), "hidden", m.fakeOre().hiddenCount());
            case "reload" -> {
                m.onReload();
                m.audit(actor(sender), "reload", "config.yml");
                m.msg(sender, "reloaded");
            }
            default -> m.msg(sender, "help");
        }
    }

    private Optional<UUID> resolve(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return Optional.of(p.getUniqueId());
        return m.api().players().uuidOf(name);
    }

    private void stats(CommandSender sender, String[] args) {
        if (args.length < 2) { m.msg(sender, "usage", "usage", "/antixray stats <玩家>"); return; }
        Optional<UUID> ou = resolve(args[1]);
        if (ou.isEmpty()) { m.msg(sender, "player-not-found", "player", args[1]); return; }
        UUID u = ou.get();
        String name = m.monitor().nameOf(u);
        MiningMonitor.Session s = m.monitor().session(u);
        Map<String, Object> window = s == null ? null : m.monitor().windowInfo(s);
        m.monitor().db().async(d -> {
            Map<String, Long> totals = m.monitor().totalsSync(u);
            long alerts = d.queryOne("SELECT COUNT(*) AS c FROM " + m.monitor().alertTable + " WHERE uuid = ?", rs -> rs.getLong("c"), u.toString()).orElse(0L);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("totals", totals);
            o.put("alerts", alerts);
            return o;
        }).whenComplete((o, err) -> m.onMain(() -> {
            if (err != null) { m.msg(sender, "database-error"); return; }
            @SuppressWarnings("unchecked") Map<String, Long> totals = (Map<String, Long>) o.get("totals");
            long common = totals.getOrDefault(MiningMonitor.COMMON_KEY, 0L);
            long rare = 0;
            for (Map.Entry<String, Long> e : totals.entrySet()) if (!e.getKey().equals(MiningMonitor.COMMON_KEY)) rare += e.getValue();
            m.msg(sender, "stats.header", "player", name, "stone", common, "rare", rare, "alerts", o.get("alerts"),
                    "per", common == 0 ? "-" : MiningMonitor.fmt(rare * 1000.0 / common));
            for (Map.Entry<String, Long> e : totals.entrySet()) {
                if (e.getKey().equals(MiningMonitor.COMMON_KEY)) continue;
                m.msg(sender, "stats.entry", "ore", e.getKey().toLowerCase(Locale.ROOT), "amount", e.getValue());
            }
            if (window != null) {
                m.msg(sender, "stats.window", "minutes", m.config().getLong("detection.window-minutes", 20),
                        "veins", window.get("veins"), "weight", window.get("weight"), "stone", window.get("stone"),
                        "direct", window.get("direct"), "alerts", window.get("alerts"));
            } else {
                m.msg(sender, "stats.offline");
            }
        }));
    }

    private void alerts(CommandSender sender, String[] args) {
        int page = 1;
        String who = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].matches("\\d{1,4}")) page = Math.max(1, Integer.parseInt(args[i]));
            else who = args[i];
        }
        UUID u = null;
        if (who != null) {
            Optional<UUID> ou = resolve(who);
            if (ou.isEmpty()) { m.msg(sender, "player-not-found", "player", who); return; }
            u = ou.get();
        }
        final UUID fu = u;
        final int fp = page;
        m.monitor().db().async(d -> m.monitor().alertsSync(fu, (fp - 1) * 10, 10)).whenComplete((list, err) -> m.onMain(() -> {
            if (err != null) { m.msg(sender, "database-error"); return; }
            if (list.isEmpty()) { m.msg(sender, "alerts.empty"); return; }
            m.msg(sender, "alerts.header", "page", fp);
            for (MiningMonitor.Alert a : list) {
                m.msg(sender, "alerts.entry", "id", a.id(), "time", TIME.format(Instant.ofEpochMilli(a.created())),
                        "player", a.name(), "type", m.raw("types." + a.type().toLowerCase(Locale.ROOT)), "detail", a.detail(),
                        "world", a.world(), "x", a.x(), "y", a.y(), "z", a.z());
            }
        }));
    }

    private void top(CommandSender sender) {
        m.monitor().db().async(d -> m.monitor().suspectsSync()).whenComplete((list, err) -> m.onMain(() -> {
            if (err != null) { m.msg(sender, "database-error"); return; }
            if (list.isEmpty()) { m.msg(sender, "top.empty"); return; }
            m.msg(sender, "top.header");
            int i = 0;
            for (Map<String, Object> r : list) {
                if (++i > 10) break;
                m.msg(sender, "top.entry", "rank", i, "player", r.get("name"), "score", r.get("score"),
                        "alerts", r.get("alerts"), "per", r.get("perThousand"), "rare", r.get("rare"), "stone", r.get("stone"));
            }
        }));
    }

    private void paper(CommandSender sender, String[] args) {
        if (!m.paperAvailable()) { m.msg(sender, "paper.unavailable"); return; }
        if (args.length < 2) {
            List<String> targets = PaperXrayConfig.targets();
            m.runAsync(() -> {
                List<String[]> lines = new ArrayList<>();
                for (String t : targets) {
                    try { lines.add(new String[]{t, AntiXrayModule.summary(PaperXrayConfig.read(t))}); }
                    catch (Exception e) { lines.add(new String[]{t, "读取失败: " + e.getMessage()}); }
                }
                m.onMain(() -> {
                    m.msg(sender, "paper.list-header");
                    for (String[] l : lines) m.msg(sender, "paper.list-entry", "target", l[0], "summary", l[1]);
                    m.msg(sender, "paper.presets", "presets", String.join(", ", PaperXrayConfig.PRESETS.keySet()));
                });
            });
            return;
        }
        String target = args[1];
        if (!target.equalsIgnoreCase(PaperXrayConfig.DEFAULTS) && Bukkit.getWorld(target) == null) {
            m.msg(sender, "paper.no-world", "world", target);
            return;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "show";
        if (action.equals("show")) {
            m.runAsync(() -> {
                try {
                    Map<String, Object> s = PaperXrayConfig.read(target);
                    m.onMain(() -> {
                        m.msg(sender, "paper.show-header", "target", target, "file", s.get("file"));
                        for (String k : PaperXrayConfig.SCALAR_KEYS) {
                            m.msg(sender, "paper.show-entry", "key", k, "value", String.valueOf(s.get(k)),
                                    "inherit", MessageService.rich(((List<?>) s.get("inherited")).contains(k) ? m.raw("paper.inherited") : ""));
                        }
                        m.msg(sender, "paper.show-list", "key", "hidden-blocks", "value", String.valueOf(s.get("hidden-blocks")));
                        m.msg(sender, "paper.show-list", "key", "replacement-blocks", "value", String.valueOf(s.get("replacement-blocks")));
                    });
                } catch (Exception e) {
                    m.onMain(() -> m.msg(sender, "paper.io-error", "error", e.getMessage()));
                }
            });
            return;
        }
        if (!sender.hasPermission("lbr.antixray.paper")) { m.msg(sender, "no-permission"); return; }
        if (action.equals("set")) {
            if (args.length < 5) { m.msg(sender, "usage", "usage", "/antixray paper <世界|defaults> set <键> <值>"); return; }
            String key = args[3].toLowerCase(Locale.ROOT);
            if (!PaperXrayConfig.SCALAR_KEYS.contains(key)) {
                m.msg(sender, "paper.bad-key", "keys", String.join(", ", PaperXrayConfig.SCALAR_KEYS));
                return;
            }
            Object value = parseValue(args[4]);
            Map<String, Object> vals = new LinkedHashMap<>();
            vals.put(key, value);
            writeAsync(sender, target, vals, key + "=" + value);
            return;
        }
        // 视为预设
        if (!PaperXrayConfig.PRESETS.containsKey(action)) {
            m.msg(sender, "paper.bad-preset", "presets", String.join(", ", PaperXrayConfig.PRESETS.keySet()));
            return;
        }
        writeAsync(sender, target, PaperXrayConfig.PRESETS.get(action).values(), "preset " + action);
    }

    static Object parseValue(String s) {
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return Boolean.parseBoolean(s);
        if (s.equalsIgnoreCase("default") || s.equalsIgnoreCase("null")) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return s; }
    }

    private void writeAsync(CommandSender sender, String target, Map<String, Object> values, String desc) {
        String actor = actor(sender);
        m.runAsync(() -> {
            try {
                PaperXrayConfig.write(target, values);
                m.audit(actor, "paper-antixray", target + ": " + desc);
                m.onMain(() -> m.msg(sender, "paper.saved", "target", target, "change", desc));
            } catch (Exception e) {
                m.onMain(() -> m.msg(sender, "paper.io-error", "error", e.getMessage()));
            }
        });
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("paper")) {
            if (args.length == 2) return filter(PaperXrayConfig.targets(), args[1]);
            if (args.length == 3) {
                List<String> o = new ArrayList<>(PaperXrayConfig.PRESETS.keySet());
                o.add("show");
                o.add("set");
                return filter(o, args[2]);
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("set")) return filter(PaperXrayConfig.SCALAR_KEYS, args[3]);
            return List.of();
        }
        if ((sub.equals("stats") || sub.equals("alerts")) && args.length == 2) return onlinePlayers(args[1]);
        return List.of();
    }
}
