package com.lunarboreal.essentials.commands;

import com.lunarboreal.essentials.Cmd;
import com.lunarboreal.essentials.EssentialsModule;
import com.lunarboreal.essentials.data.UserData;
import com.lunarboreal.essentials.feature.Guis;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 玩家状态类命令。 */
public final class PlayerCommands {

    private PlayerCommands() {}

    static final String[] NONE = new String[0];

    public static void register(EssentialsModule m) {
        // ---------------- gamemode ----------------
        m.reg((Cmd) m.cmd("gamemode", "gamemode", new String[]{"gm"}, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <survival|creative|adventure|spectator> [玩家]");
            GameMode mode = parseMode(a[0]);
            if (mode == null) throw new Cmd.Usage("/" + l + " <survival|creative|adventure|spectator> [玩家]");
            setMode(m, s, m.targetOrSelf(s, a, 1, "gamemode"), mode);
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("survival", "creative", "adventure", "spectator", "0", "1", "2", "3"))
                : a.length == 2 ? Cmd.players(a[1]) : List.of()).usage("<模式> [玩家]").description("切换游戏模式"));
        gmShortcut(m, "gms", GameMode.SURVIVAL);
        gmShortcut(m, "gmc", GameMode.CREATIVE);
        gmShortcut(m, "gma", GameMode.ADVENTURE);
        gmShortcut(m, "gmsp", GameMode.SPECTATOR);

        // ---------------- fly ----------------
        m.reg((Cmd) m.cmd("fly", "fly", NONE, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, a.length > 0 && isToggleWord(a[0]) ? 1 : 0, "fly");
            boolean v = a.length > 0 && isToggleWord(a[0]) ? parseToggle(a[0]) : !t.getAllowFlight();
            setFly(m, t, v);
            m.msg(s, "fly.set", "player", t.getName(), "state", m.onOff(v));
            m.notifyTarget(s, t, "fly.notify", "state", m.onOff(v));
            if (!t.equals(s)) m.audit(m.actor(s), "fly", t.getName() + " " + v);
        }).usage("[on|off] [玩家]").description("切换飞行"));

        // ---------------- god ----------------
        m.reg((Cmd) m.cmd("god", "god", new String[]{"godmode"}, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, a.length > 0 && isToggleWord(a[0]) ? 1 : 0, "god");
            UserData d = m.data(t);
            boolean v = a.length > 0 && isToggleWord(a[0]) ? parseToggle(a[0]) : !d.god;
            setGod(m, t, v);
            m.msg(s, "god.set", "player", t.getName(), "state", m.onOff(v));
            m.notifyTarget(s, t, "god.notify", "state", m.onOff(v));
            if (!t.equals(s)) m.audit(m.actor(s), "god", t.getName() + " " + v);
        }).usage("[on|off] [玩家]").description("切换无敌"));

        // ---------------- heal / feed ----------------
        m.reg((Cmd) m.cmd("heal", "heal", NONE, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, 0, "heal");
            heal(t);
            m.msg(s, "heal.done", "player", t.getName());
            m.notifyTarget(s, t, "heal.notify");
        }).usage("[玩家]").description("恢复生命与饱食度"));
        m.reg((Cmd) m.cmd("feed", "feed", new String[]{"eat"}, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, 0, "feed");
            feed(t);
            m.msg(s, "feed.done", "player", t.getName());
            m.notifyTarget(s, t, "feed.notify");
        }).usage("[玩家]").description("恢复饱食度"));

        // ---------------- speed ----------------
        m.reg((Cmd) m.cmd("speed", "speed", new String[]{"walkspeed", "flyspeed"}, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <0-10|reset> [walk|fly] [玩家]");
            int idx = 1;
            Boolean flyMode = null;
            if (l.equalsIgnoreCase("walkspeed")) flyMode = false;
            if (l.equalsIgnoreCase("flyspeed")) flyMode = true;
            if (a.length > 1 && (a[1].equalsIgnoreCase("walk") || a[1].equalsIgnoreCase("fly") || a[1].equalsIgnoreCase("w") || a[1].equalsIgnoreCase("f"))) {
                flyMode = a[1].toLowerCase(Locale.ROOT).startsWith("f");
                idx = 2;
            }
            Player t = m.targetOrSelf(s, a, idx, "speed");
            if (flyMode == null) flyMode = t.isFlying();
            boolean reset = a[0].equalsIgnoreCase("reset");
            double v = reset ? 1 : m.parseDouble(s, a[0]);
            int max = m.config().getInt("speed.max", 10);
            if (v < 0 || v > Math.min(10, max)) {
                m.msg(s, "speed.range", "max", Math.min(10, max));
                return;
            }
            float def = flyMode ? 0.1f : 0.2f;
            float value = v < 1 ? (float) (def * v) : (float) (def + (v - 1) / 9.0 * (1 - def));
            value = Math.max(-1f, Math.min(1f, value));
            if (flyMode) t.setFlySpeed(value);
            else t.setWalkSpeed(value);
            Object type = com.lunarboreal.api.message.MessageService.rich(m.raw(flyMode ? "speed.type-fly" : "speed.type-walk"));
            m.msg(s, "speed.set", "player", t.getName(), "type", type, "speed", reset ? "1" : a[0]);
            m.notifyTarget(s, t, "speed.notify", "type", type, "speed", reset ? "1" : a[0]);
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("1", "2", "3", "5", "10", "reset"))
                : a.length == 2 ? Cmd.opts(a[1], List.of("walk", "fly")) : a.length == 3 ? Cmd.players(a[2]) : List.of())
                .usage("<0-10|reset> [walk|fly] [玩家]").description("设置移动/飞行速度"));

        // ---------------- afk ----------------
        m.reg((Cmd) m.cmd("afk", "afk", new String[]{"away"}, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, 0, "afk");
            m.afk().setAfk(t, !m.afk().isAfk(t.getUniqueId()), true);
        }).usage("[玩家]").description("切换暂离状态"));

        // ---------------- vanish ----------------
        m.reg((Cmd) m.cmd("vanish", "vanish", new String[]{"v"}, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, a.length > 0 && isToggleWord(a[0]) ? 1 : 0, "vanish");
            boolean v = a.length > 0 && isToggleWord(a[0]) ? parseToggle(a[0]) : !m.vanish().isVanished(t.getUniqueId());
            m.vanish().setVanished(t, v, true);
            m.msg(s, "vanish.set", "player", t.getName(), "state", m.onOff(v));
            m.notifyTarget(s, t, "vanish.notify", "state", m.onOff(v));
            m.audit(m.actor(s), "vanish", t.getName() + " " + v);
        }).usage("[on|off] [玩家]").description("切换隐身"));

        // ---------------- kill / suicide ----------------
        m.reg((Cmd) m.cmd("kill", "kill", NONE, (s, l, a) -> {
            if (a.length < 1) {
                Player self = m.self(s);
                self.setHealth(0);
                return;
            }
            Player t = m.online(s, a[0]);
            if (!t.equals(s) && !s.hasPermission("lbr.essentials.kill.others")) {
                m.msg(s, "no-permission-others");
                return;
            }
            if (t.hasPermission("lbr.essentials.kill.exempt") && !t.equals(s) && !s.hasPermission("lbr.essentials.kill.force")) {
                m.msg(s, "kill.exempt", "player", t.getName());
                return;
            }
            t.setHealth(0);
            m.msg(s, "kill.done", "player", t.getName());
            m.audit(m.actor(s), "kill", t.getName());
        }).usage("[玩家]").description("杀死玩家"));
        m.reg((Cmd) m.cmd("suicide", "suicide", NONE, (s, l, a) -> {
            Player p = m.self(s);
            p.setHealth(0);
            m.msg(p, "suicide.done");
        }).playerOnly(true).description("自杀"));

        // ---------------- burn / ext / lightning ----------------
        m.reg((Cmd) m.cmd("burn", "burn", NONE, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <玩家> [秒]");
            Player t = m.online(s, a[0]);
            int sec = a.length > 1 ? m.parseInt(s, a[1]) : 10;
            t.setFireTicks(Math.max(0, sec) * 20);
            m.msg(s, "burn.done", "player", t.getName(), "seconds", sec);
            m.audit(m.actor(s), "burn", t.getName() + " " + sec + "s");
        }).usage("<玩家> [秒]").description("点燃玩家"));
        m.reg((Cmd) m.cmd("ext", "ext", new String[]{"extinguish"}, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, 0, "ext");
            t.setFireTicks(0);
            m.msg(s, "ext.done", "player", t.getName());
        }).usage("[玩家]").description("灭火"));
        m.reg((Cmd) m.cmd("lightning", "lightning", new String[]{"smite", "strike"}, (s, l, a) -> {
            Location loc;
            String name;
            if (a.length > 0) {
                Player t = m.online(s, a[0]);
                loc = t.getLocation();
                name = t.getName();
            } else {
                Player p = m.self(s);
                Block b = p.getTargetBlockExact(200);
                loc = b == null ? p.getLocation() : b.getLocation();
                name = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            }
            loc.getWorld().strikeLightning(loc);
            m.msg(s, "lightning.done", "target", name);
            m.audit(m.actor(s), "lightning", name);
        }).usage("[玩家]").description("召唤闪电"));

        // ---------------- invsee / enderchest ----------------
        m.reg((Cmd) m.cmd("invsee", "invsee", new String[]{"inv"}, (s, l, a) -> {
            Player p = m.self(s);
            if (a.length < 1) throw new Cmd.Usage();
            Player t = m.online(s, a[0]);
            if (t.equals(p)) {
                m.msg(s, "invsee.self");
                return;
            }
            boolean modify = p.hasPermission("lbr.essentials.invsee.modify")
                    && !(t.hasPermission("lbr.essentials.invsee.exempt") && !p.hasPermission("lbr.essentials.invsee.modify.exempt"));
            p.openInventory(t.getInventory());
            if (!modify) Guis.READONLY_VIEWERS.add(p.getUniqueId());
            m.msg(s, modify ? "invsee.open" : "invsee.open-readonly", "player", t.getName());
            m.audit(m.actor(s), "invsee", t.getName());
        }).playerOnly(true).usage("<玩家>").description("查看玩家背包"));
        m.reg((Cmd) m.cmd("enderchest", "enderchest", new String[]{"ec", "echest"}, (s, l, a) -> {
            Player p = m.self(s);
            Player t = a.length > 0 ? m.targetOrSelf(s, a, 0, "enderchest") : p;
            p.openInventory(t.getEnderChest());
            if (!t.equals(p)) {
                if (!p.hasPermission("lbr.essentials.enderchest.modify")) Guis.READONLY_VIEWERS.add(p.getUniqueId());
                m.audit(m.actor(s), "enderchest", t.getName());
            }
        }).playerOnly(true).usage("[玩家]").description("打开末影箱"));

        // ---------------- clear ----------------
        m.reg((Cmd) m.cmd("clear", "clear", new String[]{"ci", "clearinventory"}, (s, l, a) -> {
            boolean selfAll = a.length == 1 && a[0].equalsIgnoreCase("all") && Cmd.findOnline("all") == null;
            Player t = selfAll ? m.self(s) : m.targetOrSelf(s, a, 0, "clear");
            boolean all = selfAll || (a.length > 1 && a[1].equalsIgnoreCase("all"));
            if (all) {
                t.getInventory().clear();
            } else {
                for (int i = 0; i < 36; i++) t.getInventory().setItem(i, null);
                t.getInventory().setItemInOffHand(null);
            }
            m.msg(s, "clear.done", "player", t.getName());
            m.notifyTarget(s, t, "clear.notify");
            if (!t.equals(s)) m.audit(m.actor(s), "clear", t.getName());
        }).tab((s, a) -> a.length == 1 ? Cmd.players(a[0]) : a.length == 2 ? Cmd.opts(a[1], List.of("all")) : List.of())
                .usage("[玩家] [all]").description("清空背包（all 包含盔甲）"));

        // ---------------- exp ----------------
        m.reg((Cmd) m.cmd("exp", "exp", new String[]{"xp", "experience"}, (s, l, a) -> {
            if (a.length == 0 || a[0].equalsIgnoreCase("show")) {
                Player t = m.targetOrSelf(s, a, 1, "exp");
                m.msg(s, "exp.show", "player", t.getName(), "level", t.getLevel(), "total", t.calculateTotalExperiencePoints());
                return;
            }
            String op = a[0].toLowerCase(Locale.ROOT);
            if (!List.of("give", "set", "take").contains(op) || a.length < 2) throw new Cmd.Usage("/" + l + " <give|set|take|show> <数量[L]> [玩家]");
            String amountStr = a[1];
            boolean levels = amountStr.toUpperCase(Locale.ROOT).endsWith("L");
            int amount = m.parseInt(s, levels ? amountStr.substring(0, amountStr.length() - 1) : amountStr);
            Player t = m.targetOrSelf(s, a, 2, "exp");
            if (levels) {
                int cur = t.getLevel();
                int next = switch (op) { case "give" -> cur + amount; case "take" -> cur - amount; default -> amount; };
                t.setLevel(Math.max(0, next));
            } else {
                int cur = t.calculateTotalExperiencePoints();
                int next = switch (op) { case "give" -> cur + amount; case "take" -> cur - amount; default -> amount; };
                t.setExperienceLevelAndProgress(Math.max(0, next));
            }
            m.msg(s, "exp.done", "player", t.getName(), "level", t.getLevel(), "total", t.calculateTotalExperiencePoints());
            m.audit(m.actor(s), "exp", op + " " + amountStr + " " + t.getName());
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("give", "set", "take", "show"))
                : a.length == 2 ? Cmd.opts(a[1], List.of("10", "100", "10L", "30L")) : a.length == 3 ? Cmd.players(a[2]) : List.of())
                .usage("<give|set|take|show> <数量[L]> [玩家]").description("经验管理（数量后加 L 表示等级）"));

        // ---------------- ping / getpos / depth / compass / near ----------------
        m.reg((Cmd) m.cmd("ping", "ping", new String[]{"latency"}, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, 0, "ping");
            m.msg(s, "ping", "player", t.getName(), "ping", t.getPing());
        }).usage("[玩家]").description("查看延迟"));
        m.reg((Cmd) m.cmd("getpos", "getpos", new String[]{"coords", "whereami"}, (s, l, a) -> {
            Player t = m.targetOrSelf(s, a, 0, "getpos");
            Location loc = t.getLocation();
            m.msg(s, "getpos", "player", t.getName(), "world", loc.getWorld().getName(),
                    "x", fmt(loc.getX()), "y", fmt(loc.getY()), "z", fmt(loc.getZ()),
                    "yaw", fmt(loc.getYaw()), "pitch", fmt(loc.getPitch()));
        }).usage("[玩家]").description("查看坐标"));
        m.reg((Cmd) m.cmd("depth", "depth", new String[]{"height"}, (s, l, a) -> {
            Player p = m.self(s);
            int diff = p.getLocation().getBlockY() - p.getWorld().getSeaLevel();
            m.msg(s, diff > 0 ? "depth.above" : diff < 0 ? "depth.below" : "depth.at", "blocks", Math.abs(diff));
        }).playerOnly(true).description("相对海平面的高度"));
        m.reg((Cmd) m.cmd("compass", "compass", new String[]{"direction"}, (s, l, a) -> {
            Player p = m.self(s);
            float yaw = (p.getLocation().getYaw() % 360 + 360) % 360;
            String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
            String d = dirs[(int) Math.round(yaw / 45.0) % 8];
            m.msg(s, "compass", "direction", com.lunarboreal.api.message.MessageService.rich(m.raw("compass-dir." + d)), "yaw", fmt(yaw));
        }).playerOnly(true).description("查看朝向"));
        m.reg((Cmd) m.cmd("near", "near", new String[]{"nearby"}, (s, l, a) -> {
            Player p = m.self(s);
            int def = m.config().getInt("near.default-radius", 100);
            int max = m.config().getInt("near.max-radius", 500);
            int r = a.length > 0 ? m.parseInt(s, a[0]) : def;
            r = Math.max(1, Math.min(r, max));
            List<String> list = new ArrayList<>();
            double rsq = (double) r * r;
            for (Player o : p.getWorld().getPlayers()) {
                if (o.equals(p) || !m.vanish().canSee(p, o)) continue;
                double dsq = o.getLocation().distanceSquared(p.getLocation());
                if (dsq <= rsq) list.add(o.getName() + "(" + (int) Math.sqrt(dsq) + "m)");
            }
            if (list.isEmpty()) m.msg(s, "near.none", "radius", r);
            else m.msg(s, "near.list", "radius", r, "players", String.join(", ", list));
        }).playerOnly(true).usage("[半径]").description("附近的玩家"));

        // ---------------- jump ----------------
        m.reg((Cmd) m.cmd("jump", "jump", new String[]{"j", "jumpto"}, (s, l, a) -> {
            Player p = m.self(s);
            Block b = p.getTargetBlockExact(m.config().getInt("jump.max-distance", 200));
            if (b == null) {
                m.msg(s, "jump.no-block");
                return;
            }
            Block top = b;
            int maxY = b.getWorld().getMaxHeight();
            while (top.getY() < maxY - 2 && (!top.getRelative(0, 1, 0).isPassable() || !top.getRelative(0, 2, 0).isPassable())) {
                top = top.getRelative(0, 1, 0);
            }
            Location to = top.getLocation().add(0.5, 1, 0.5);
            to.setYaw(p.getLocation().getYaw());
            to.setPitch(p.getLocation().getPitch());
            p.teleport(to);
            m.msg(s, "jump.done");
        }).playerOnly(true).description("跳到看向的方块"));

        // ---------------- sudo ----------------
        m.reg((Cmd) m.cmd("sudo", "sudo", NONE, (s, l, a) -> {
            if (a.length < 2) throw new Cmd.Usage("/" + l + " <玩家> <命令|c:聊天>");
            Player t = m.online(s, a[0]);
            if (t.hasPermission("lbr.essentials.sudo.exempt") && !(s instanceof org.bukkit.command.ConsoleCommandSender)) {
                m.msg(s, "sudo.exempt", "player", t.getName());
                return;
            }
            String line = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
            if (line.startsWith("c:")) t.chat(line.substring(2));
            else t.performCommand(line.startsWith("/") ? line.substring(1) : line);
            m.msg(s, "sudo.done", "player", t.getName(), "command", line);
            m.audit(m.actor(s), "sudo", t.getName() + ": " + line);
        }).usage("<玩家> <命令|c:聊天>").description("让玩家执行命令或发言"));
    }

    // ---------------- 公共操作（Web 面板复用） ----------------

    public static void heal(Player t) {
        AttributeInstance max = t.getAttribute(Attribute.MAX_HEALTH);
        t.setHealth(max == null ? 20 : max.getValue());
        feed(t);
        t.setFireTicks(0);
        for (PotionEffect eff : t.getActivePotionEffects()) {
            if (!eff.getType().isInstant() && isNegative(eff)) t.removePotionEffect(eff.getType());
        }
    }

    private static boolean isNegative(PotionEffect eff) {
        try {
            return eff.getType().getCategory() == org.bukkit.potion.PotionEffectTypeCategory.HARMFUL;
        } catch (Throwable ex) {
            return false;
        }
    }

    public static void feed(Player t) {
        t.setFoodLevel(20);
        t.setSaturation(20f);
        t.setExhaustion(0f);
    }

    public static void setFly(EssentialsModule m, Player t, boolean v) {
        t.setAllowFlight(v);
        if (!v) t.setFlying(false);
        UserData d = m.data(t);
        d.fly = v;
        d.markDirty();
    }

    public static void setGod(EssentialsModule m, Player t, boolean v) {
        UserData d = m.data(t);
        d.god = v;
        d.markDirty();
        if (v) {
            heal(t);
        }
    }

    public static void setMode(EssentialsModule m, CommandSender s, Player t, GameMode mode) {
        String node = "lbr.essentials.gamemode." + mode.name().toLowerCase(Locale.ROOT);
        if (m.config().getBoolean("gamemode.per-mode-permission", false) && !s.hasPermission(node)) {
            m.msg(s, "gamemode.no-mode-permission", "mode", modeName(m, mode));
            return;
        }
        t.setGameMode(mode);
        m.msg(s, "gamemode.set", "player", t.getName(), "mode", modeName(m, mode));
        m.notifyTarget(s, t, "gamemode.notify", "mode", modeName(m, mode));
        if (!t.equals(s)) m.audit(m.actor(s), "gamemode", t.getName() + " " + mode.name());
    }

    public static com.lunarboreal.api.message.MessageService.Rich modeName(EssentialsModule m, GameMode mode) {
        return com.lunarboreal.api.message.MessageService.rich(m.raw("gamemode.names." + mode.name().toLowerCase(Locale.ROOT)));
    }

    public static GameMode parseMode(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival", "生存" -> GameMode.SURVIVAL;
            case "1", "c", "creative", "创造" -> GameMode.CREATIVE;
            case "2", "a", "adventure", "冒险" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator", "旁观" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static void gmShortcut(EssentialsModule m, String name, GameMode mode) {
        m.reg((Cmd) m.cmd(name, "gamemode", NONE, (s, l, a) ->
                setMode(m, s, m.targetOrSelf(s, a, 0, "gamemode"), mode)).usage("[玩家]").description("切换到" + mode.name()));
    }

    static boolean isToggleWord(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "on", "off", "true", "false", "enable", "disable", "开", "关" -> true;
            default -> false;
        };
    }

    static boolean parseToggle(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "开" -> true;
            default -> false;
        };
    }

    static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
