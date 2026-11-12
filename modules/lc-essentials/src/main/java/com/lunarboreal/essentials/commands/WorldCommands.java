package com.lunarboreal.essentials.commands;

import com.lunarboreal.essentials.Cmd;
import com.lunarboreal.essentials.EssentialsModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.TreeType;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Projectile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 世界与服务器管理命令。 */
public final class WorldCommands {

    private WorldCommands() {}

    private static final String[] NONE = new String[0];

    public static void register(EssentialsModule m) {
        // ---------------- time ----------------
        m.reg((Cmd) m.cmd("time", "time", new String[]{"day", "night", "noon", "midnight"}, (s, l, a) -> {
            String label = l.toLowerCase(Locale.ROOT);
            String op;
            String value = null;
            int worldIdx;
            if (List.of("day", "night", "noon", "midnight").contains(label)) {
                op = "set";
                value = label;
                worldIdx = 0;
            } else {
                if (a.length == 0) {
                    World w = senderWorld(s);
                    m.msg(s, "time.show", "world", w.getName(), "time", formatTime(w.getTime()), "ticks", w.getTime());
                    return;
                }
                op = a[0].toLowerCase(Locale.ROOT);
                if (!op.equals("set") && !op.equals("add")) {
                    value = op;
                    op = "set";
                    worldIdx = 1;
                } else {
                    if (a.length < 2) throw new Cmd.Usage("/" + l + " <set|add> <值> [世界|all]");
                    value = a[1];
                    worldIdx = 2;
                }
            }
            long ticks = parseTime(value);
            if (ticks < 0) {
                m.msg(s, "time.invalid", "value", value);
                return;
            }
            List<World> worlds = worlds(m, s, a, worldIdx);
            for (World w : worlds) {
                if (op.equals("add")) w.setFullTime(w.getFullTime() + ticks);
                else {
                    long full = w.getFullTime();
                    long base = full - Math.floorMod(full, 24000L);
                    w.setFullTime(base + ticks + (ticks < Math.floorMod(full, 24000L) ? 24000L : 0));
                }
            }
            String names = String.join(", ", worlds.stream().map(World::getName).toList());
            m.msg(s, op.equals("add") ? "time.added" : "time.set", "world", names, "time", op.equals("add") ? String.valueOf(ticks) : formatTime(ticks));
            m.audit(m.actor(s), "time", op + " " + value + " " + names);
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("set", "add", "day", "night", "noon", "midnight"))
                : a.length == 2 ? Cmd.opts(a[1], List.of("day", "night", "noon", "midnight", "1000", "13000"))
                : a.length == 3 ? Cmd.opts(a[2], worldNames(true)) : List.of())
                .usage("[set|add] <day|night|值> [世界|all]").description("设置世界时间"));

        // ---------------- weather ----------------
        m.reg((Cmd) m.cmd("weather", "weather", new String[]{"sun", "rain", "storm", "thunder"}, (s, l, a) -> {
            String label = l.toLowerCase(Locale.ROOT);
            String type;
            int idx;
            if (List.of("sun", "rain", "storm", "thunder").contains(label)) {
                type = label;
                idx = 0;
            } else {
                if (a.length == 0) throw new Cmd.Usage("/" + l + " <sun|rain|storm> [秒] [世界]");
                type = a[0].toLowerCase(Locale.ROOT);
                idx = 1;
            }
            int duration = 0;
            if (a.length > idx && a[idx].matches("\\d+")) {
                duration = Integer.parseInt(a[idx]) * 20;
                idx++;
            }
            List<World> worlds = worlds(m, s, a, idx);
            for (World w : worlds) {
                switch (type) {
                    case "sun", "clear" -> {
                        w.setStorm(false);
                        w.setThundering(false);
                        w.setClearWeatherDuration(duration > 0 ? duration : 20 * 60 * 10);
                    }
                    case "rain" -> {
                        w.setStorm(true);
                        w.setThundering(false);
                        if (duration > 0) w.setWeatherDuration(duration);
                    }
                    case "storm", "thunder" -> {
                        w.setStorm(true);
                        w.setThundering(true);
                        if (duration > 0) {
                            w.setWeatherDuration(duration);
                            w.setThunderDuration(duration);
                        }
                    }
                    default -> throw new Cmd.Usage("/" + l + " <sun|rain|storm> [秒] [世界]");
                }
            }
            String names = String.join(", ", worlds.stream().map(World::getName).toList());
            m.msg(s, "weather.set", "world", names, "weather", com.lunarboreal.api.message.MessageService.rich(m.raw("weather.names." + (type.equals("clear") ? "sun" : type.equals("thunder") ? "storm" : type))));
            m.audit(m.actor(s), "weather", type + " " + names);
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("sun", "rain", "storm")) : a.length == 2 ? Cmd.opts(a[1], worldNames(true)) : List.of())
                .usage("<sun|rain|storm> [秒] [世界|all]").description("设置天气"));

        // ---------------- ptime / pweather ----------------
        m.reg((Cmd) m.cmd("ptime", "ptime", new String[]{"playertime"}, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <reset|day|night|值|@值(固定)> [玩家]");
            Player t = m.targetOrSelf(s, a, 1, "ptime");
            String v = a[0].toLowerCase(Locale.ROOT);
            if (v.equals("reset")) {
                t.resetPlayerTime();
                m.msg(s, "ptime.reset", "player", t.getName());
                return;
            }
            boolean fixed = v.startsWith("@");
            long ticks = parseTime(fixed ? v.substring(1) : v);
            if (ticks < 0) { m.msg(s, "time.invalid", "value", a[0]); return; }
            if (fixed) t.setPlayerTime(ticks, false);
            else {
                long worldTime = Math.floorMod(t.getWorld().getTime(), 24000L);
                t.setPlayerTime(ticks - worldTime, true);
            }
            m.msg(s, "ptime.set", "player", t.getName(), "time", formatTime(ticks));
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("reset", "day", "night", "noon", "midnight", "@day", "@night"))
                : a.length == 2 ? Cmd.players(a[1]) : List.of()).usage("<reset|day|night|值|@值> [玩家]").description("设置个人时间"));
        m.reg((Cmd) m.cmd("pweather", "pweather", new String[]{"playerweather"}, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <reset|sun|rain> [玩家]");
            Player t = m.targetOrSelf(s, a, 1, "pweather");
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "reset" -> {
                    t.resetPlayerWeather();
                    m.msg(s, "pweather.reset", "player", t.getName());
                }
                case "sun", "clear" -> {
                    t.setPlayerWeather(WeatherType.CLEAR);
                    m.msg(s, "pweather.set", "player", t.getName(), "weather", com.lunarboreal.api.message.MessageService.rich(m.raw("weather.names.sun")));
                }
                case "rain", "storm", "downfall" -> {
                    t.setPlayerWeather(WeatherType.DOWNFALL);
                    m.msg(s, "pweather.set", "player", t.getName(), "weather", com.lunarboreal.api.message.MessageService.rich(m.raw("weather.names.rain")));
                }
                default -> throw new Cmd.Usage("/" + l + " <reset|sun|rain> [玩家]");
            }
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("reset", "sun", "rain")) : a.length == 2 ? Cmd.players(a[1]) : List.of())
                .usage("<reset|sun|rain> [玩家]").description("设置个人天气"));

        // ---------------- spawnmob ----------------
        m.reg((Cmd) m.cmd("spawnmob", "spawnmob", new String[]{"mob", "smob"}, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <生物> [数量] [玩家]");
            EntityType type = entityType(a[0]);
            if (type == null || !type.isSpawnable() || !type.isAlive() || type == EntityType.PLAYER) {
                m.msg(s, "spawnmob.unknown", "mob", a[0]);
                return;
            }
            List<String> blacklist = m.config().getStringList("spawnmob.blacklist");
            if (blacklist.stream().anyMatch(b -> b.equalsIgnoreCase(type.name())) && !s.hasPermission("lc.essentials.spawnmob.bypass")) {
                m.msg(s, "spawnmob.blacklisted", "mob", type.name().toLowerCase(Locale.ROOT));
                return;
            }
            int count = a.length > 1 ? m.parseInt(s, a[1]) : 1;
            int max = m.config().getInt("spawnmob.max", 50);
            count = Math.max(1, Math.min(count, max));
            Location loc;
            if (a.length > 2) {
                Player t = m.online(s, a[2]);
                if (!t.equals(s) && !s.hasPermission("lc.essentials.spawnmob.others")) { m.msg(s, "no-permission-others"); return; }
                loc = t.getLocation();
            } else {
                Player p = m.self(s);
                Block b = p.getTargetBlockExact(100);
                loc = b == null ? p.getLocation() : b.getLocation().add(0.5, 1, 0.5);
            }
            for (int i = 0; i < count; i++) loc.getWorld().spawnEntity(loc, type);
            m.msg(s, "spawnmob.done", "mob", type.name().toLowerCase(Locale.ROOT), "count", count);
            m.audit(m.actor(s), "spawnmob", type.name() + " x" + count);
        }).tab((s, a) -> {
            if (a.length == 1) {
                List<String> out = new ArrayList<>();
                for (EntityType t : EntityType.values()) {
                    if (t.isSpawnable() && t.isAlive() && t != EntityType.PLAYER) out.add(t.name().toLowerCase(Locale.ROOT));
                }
                return Cmd.opts(a[0], out);
            }
            return a.length == 3 ? Cmd.players(a[2]) : List.of();
        }).usage("<生物> [数量] [玩家]").description("生成生物"));

        // ---------------- butcher / remove ----------------
        m.reg((Cmd) m.cmd("butcher", "butcher", new String[]{"remove", "killall"}, (s, l, a) -> {
            String kind = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "monsters";
            int radius = a.length > 1 ? m.parseInt(s, a[1]) : m.config().getInt("butcher.default-radius", 0);
            List<World> worlds;
            Location center = null;
            if (s instanceof Player p && radius > 0) {
                center = p.getLocation();
                worlds = List.of(p.getWorld());
            } else if (s instanceof Player p && a.length <= 2) {
                worlds = List.of(p.getWorld());
            } else {
                worlds = worlds(m, s, a, 2);
            }
            int removed = 0;
            double rsq = (double) radius * radius;
            for (World w : worlds) {
                for (Entity e : w.getEntities()) {
                    if (e instanceof Player) continue;
                    if (center != null && (e.getWorld() != center.getWorld() || e.getLocation().distanceSquared(center) > rsq)) continue;
                    if (!matches(kind, e)) continue;
                    e.remove();
                    removed++;
                }
            }
            m.msg(s, "butcher.done", "count", removed, "type", kind);
            m.audit(m.actor(s), "butcher", kind + " r=" + radius + " removed=" + removed);
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("monsters", "animals", "ambient", "items", "drops", "xp", "arrows", "boats", "minecarts", "all", "tamed", "named"))
                : a.length == 2 ? Cmd.opts(a[1], List.of("0", "50", "100")) : a.length == 3 ? Cmd.opts(a[2], worldNames(true)) : List.of())
                .usage("[monsters|animals|ambient|items|xp|arrows|all|<实体类型>] [半径(0=整个世界)] [世界|all]").description("清除实体"));

        // ---------------- tree / bigtree ----------------
        m.reg((Cmd) m.cmd("tree", "tree", NONE, (s, l, a) -> {
            Player p = m.self(s);
            TreeType type = TreeType.TREE;
            if (a.length > 0) {
                type = treeType(a[0]);
                if (type == null) { m.msg(s, "tree.unknown", "type", a[0]); return; }
            }
            plantTree(m, p, type);
        }).tab((s, a) -> {
            if (a.length != 1) return List.of();
            List<String> out = new ArrayList<>();
            for (TreeType t : TreeType.values()) out.add(t.name().toLowerCase(Locale.ROOT));
            return Cmd.opts(a[0], out);
        }).playerOnly(true).usage("[类型]").description("在看向的位置生成树"));
        m.reg((Cmd) m.cmd("bigtree", "tree", new String[]{"largetree"}, (s, l, a) -> {
            Player p = m.self(s);
            TreeType type = a.length > 0 && a[0].equalsIgnoreCase("redwood") ? TreeType.TALL_REDWOOD
                    : a.length > 0 && a[0].equalsIgnoreCase("jungle") ? TreeType.JUNGLE
                    : a.length > 0 && a[0].equalsIgnoreCase("dark_oak") ? TreeType.DARK_OAK : TreeType.BIG_TREE;
            plantTree(m, p, type);
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("tree", "redwood", "jungle", "dark_oak")) : List.of())
                .playerOnly(true).usage("[tree|redwood|jungle|dark_oak]").description("生成大树"));
    }

    @SuppressWarnings("deprecation")
    private static void plantTree(EssentialsModule m, Player p, TreeType type) {
        Block b = p.getTargetBlockExact(100);
        if (b == null) { m.msg(p, "jump.no-block"); return; }
        Location loc = b.getLocation().add(0, 1, 0);
        boolean ok = p.getWorld().generateTree(loc, type);
        m.msg(p, ok ? "tree.done" : "tree.failed", "type", type.name().toLowerCase(Locale.ROOT));
    }

    private static boolean matches(String kind, Entity e) {
        boolean protectedEntity = e instanceof Villager || e instanceof ArmorStand
                || (e instanceof Tameable t && t.isTamed()) || e.customName() != null;
        return switch (kind) {
            case "monsters", "monster", "hostile", "mobs" -> e instanceof Monster && e.customName() == null;
            case "animals", "animal", "passive" -> (e instanceof Animals || e instanceof WaterMob) && !protectedEntity;
            case "ambient" -> e instanceof Ambient;
            case "items", "drops", "item" -> e instanceof Item;
            case "xp", "exp", "orbs" -> e instanceof ExperienceOrb;
            case "arrows", "projectiles" -> e instanceof Projectile;
            case "boats" -> e.getType().name().endsWith("_BOAT") || e.getType().name().endsWith("_RAFT");
            case "minecarts" -> e.getType().name().contains("MINECART");
            case "tamed" -> e instanceof Tameable t && t.isTamed();
            case "named" -> e.customName() != null && !(e instanceof Player);
            case "all", "living" -> e instanceof LivingEntity && !protectedEntity;
            default -> {
                EntityType t = entityType(kind);
                yield t != null && e.getType() == t;
            }
        };
    }

    private static World senderWorld(CommandSender s) {
        if (s instanceof Player p) return p.getWorld();
        if (s instanceof org.bukkit.command.BlockCommandSender b) return b.getBlock().getWorld();
        return Bukkit.getWorlds().get(0);
    }

    private static List<World> worlds(EssentialsModule m, CommandSender s, String[] a, int idx) {
        if (a.length > idx) {
            if (a[idx].equalsIgnoreCase("all") || a[idx].equals("*")) return Bukkit.getWorlds();
            World w = Bukkit.getWorld(a[idx]);
            if (w == null) {
                m.msg(s, "world-not-found", "world", a[idx]);
                throw new Cmd.Stop();
            }
            return List.of(w);
        }
        return List.of(senderWorld(s));
    }

    static List<String> worldNames(boolean withAll) {
        List<String> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) out.add(w.getName());
        if (withAll) out.add("all");
        return out;
    }

    /** 解析时间：day/night/noon/midnight、纯数字 ticks、12:30 形式、"8am"。返回 -1 表示无效。 */
    static long parseTime(String v) {
        v = v.toLowerCase(Locale.ROOT);
        switch (v) {
            case "day", "morning", "白天" -> { return 1000; }
            case "noon", "中午" -> { return 6000; }
            case "sunset", "evening", "傍晚" -> { return 12000; }
            case "night", "夜晚" -> { return 13000; }
            case "midnight", "午夜" -> { return 18000; }
            case "sunrise", "dawn" -> { return 23000; }
            default -> { }
        }
        try {
            if (v.matches("\\d+t?")) return Long.parseLong(v.replace("t", ""));
            if (v.matches("\\d{1,2}:\\d{2}")) {
                String[] p = v.split(":");
                int h = Integer.parseInt(p[0]);
                int mi = Integer.parseInt(p[1]);
                if (h > 23 || mi > 59) return -1;
                return Math.floorMod((h - 6) * 1000L + mi * 1000L / 60, 24000L);
            }
            if (v.matches("\\d{1,2}(am|pm)")) {
                int h = Integer.parseInt(v.substring(0, v.length() - 2)) % 12;
                if (v.endsWith("pm")) h += 12;
                return Math.floorMod((h - 6) * 1000L, 24000L);
            }
        } catch (NumberFormatException ignored) {
            return -1;
        }
        return -1;
    }

    static String formatTime(long ticks) {
        long t = Math.floorMod(ticks, 24000L);
        long hours = (t / 1000 + 6) % 24;
        long minutes = (t % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    static EntityType entityType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        try {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(n.contains(":") ? n : "minecraft:" + n);
            EntityType t = key == null ? null : Registry.ENTITY_TYPE.get(key);
            if (t != null) return t;
        } catch (IllegalArgumentException ignored) {
            // 非法 key，继续尝试枚举名
        }
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static TreeType treeType(String name) {
        try {
            return TreeType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
