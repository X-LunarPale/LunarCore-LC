package com.lunarboreal.essentials;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.essentials.commands.InfoCommands;
import com.lunarboreal.essentials.commands.ItemCommands;
import com.lunarboreal.essentials.commands.KitCommands;
import com.lunarboreal.essentials.commands.PlayerCommands;
import com.lunarboreal.essentials.commands.WorldCommands;
import com.lunarboreal.essentials.data.UserData;
import com.lunarboreal.essentials.data.UserManager;
import com.lunarboreal.essentials.feature.AfkManager;
import com.lunarboreal.essentials.feature.EssListener;
import com.lunarboreal.essentials.feature.KitManager;
import com.lunarboreal.essentials.feature.VanishManager;
import com.lunarboreal.essentials.feature.WorthManager;
import com.lunarboreal.essentials.web.EssWeb;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class EssentialsModule extends LbrModule {

    /** 与原版同名、需要抢占的命令。 */
    private static final Set<String> VANILLA = Set.of("gamemode", "give", "kill", "time", "weather", "list",
            "enchant", "clear", "xp", "experience", "item");

    private UserManager users;
    private AfkManager afk;
    private VanishManager vanish;
    private KitManager kits;
    private WorthManager worth;
    private final Set<String> registered = new HashSet<>();

    @Override
    public void onEnable() {
        reloadConfig();
        users = new UserManager(this);
        afk = new AfkManager(this);
        vanish = new VanishManager(this);
        kits = new KitManager(this);
        worth = new WorthManager(this);
        kits.load();
        worth.load();

        // 建表与在线玩家数据（热重载场景）异步加载
        List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        runAsync(() -> {
            try {
                users.init();
            } catch (Exception e) {
                logger().severe("初始化 essentials_users 表失败: " + e.getMessage());
            }
            for (UUID u : online) users.loadBlocking(u);
            runTask(() -> {
                for (UUID u : online) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) restoreState(p, false);
                }
            });
        });

        registerListener(new EssListener(this));
        afk.start();

        PlayerCommands.register(this);
        ItemCommands.register(this);
        WorldCommands.register(this);
        InfoCommands.register(this);
        KitCommands.register(this);

        int saveInterval = Math.max(30, config().getInt("data.save-interval-seconds", 120));
        runTaskTimer(users::flushDirtyAsync, saveInterval * 20L, saveInterval * 20L);

        placeholder((player, key) -> {
            if (player == null) return null;
            UUID u = player.getUniqueId();
            return switch (key.toLowerCase(Locale.ROOT)) {
                case "afk" -> bool(afk.isAfk(u));
                case "afk_time" -> afk.isAfk(u) ? com.lunarboreal.api.util.Durations.format(System.currentTimeMillis() - afk.afkSince(u)) : "";
                case "vanished" -> bool(vanish.isVanished(u));
                case "god" -> bool(users.isLoaded(u) && users.get(u).god);
                case "fly" -> {
                    Player p = player.getPlayer();
                    yield bool(p != null && p.getAllowFlight());
                }
                case "afk_count" -> String.valueOf(afk.afkCount());
                case "vanished_count" -> String.valueOf(vanish.count());
                default -> null;
            };
        });

        new EssWeb(this).register();

        provide(com.lunarboreal.api.service.EssentialsService.class, new com.lunarboreal.api.service.EssentialsService() {
            @Override public boolean isVanished(UUID player) { return vanish.isVanished(player); }
            @Override public boolean isAfk(UUID player) { return afk.isAfk(player); }
            @Override public boolean isGod(UUID player) { return users.isLoaded(player) && users.get(player).god; }
            @Override public boolean canSee(CommandSender viewer, Player target) { return vanish.canSee(viewer, target); }
            @Override public long afkSince(UUID player) { return afk.isAfk(player) ? afk.afkSince(player) : 0L; }
        });

        // 普通玩家默认拥有的权限（可在 config.yml default-permissions 中调整）
        List<String> defaults = config().getStringList("default-permissions");
        defaultPermissions(defaults.toArray(new String[0]));
    }

    private String bool(boolean b) {
        return b ? config().getString("placeholder.true", "true") : config().getString("placeholder.false", "false");
    }

    @Override
    public void onDisable() {
        if (vanish != null) vanish.showAllOnDisable();
        if (afk != null) afk.stop();
        if (users != null) users.saveAllBlocking();
        if (kits != null) kits.saveBlocking();
        if (worth != null) worth.saveBlocking();
        registered.clear();
    }

    @Override
    public void onReload() {
        reloadConfig();
        kits.load();
        worth.load();
    }

    // ---------------- 状态恢复 ----------------

    /** 玩家加入（或模块启用时已在线）后恢复持久化状态。 */
    public void restoreState(Player p, boolean join) {
        UserData d = users.get(p.getUniqueId());
        if (d.fly && p.hasPermission("lbr.essentials.fly")) {
            p.setAllowFlight(true);
        }
        if (d.god && !p.hasPermission("lbr.essentials.god")) {
            d.god = false;
            d.markDirty();
        }
        if ((d.vanish && p.hasPermission("lbr.essentials.vanish"))
                || (join && p.hasPermission("lbr.essentials.vanish.onjoin"))) {
            vanish.setVanished(p, true, false);
        } else if (d.vanish) {
            d.vanish = false;
            d.markDirty();
        }
        vanish.hideVanishedFrom(p);
        afk.touch(p);
    }

    // ---------------- 命令注册 ----------------

    public void reg(Cmd cmd) {
        List<String> disabled = config().getStringList("disabled-commands");
        for (String s : disabled) {
            if (s.equalsIgnoreCase(cmd.name())) return;
        }
        if (VANILLA.contains(cmd.name()) && config().getBoolean("override-vanilla", true)
                && !config().getStringList("no-override").contains(cmd.name())) {
            cmd.override(true);
        }
        registerCommand(cmd);
        registered.add(cmd.name());
    }

    public Cmd cmd(String name, String permKey, String[] aliases, Cmd.Exec exec) {
        return new Cmd(this, name, permKey, aliases, exec);
    }

    public Set<String> registeredCommands() {
        return registered;
    }

    // ---------------- 参数工具 ----------------

    public Player self(CommandSender s) {
        if (s instanceof Player p) return p;
        msg(s, "player-only");
        throw new Cmd.Stop();
    }

    /** 若 args.length > idx 则解析 args[idx] 为在线玩家（需要 .others 权限），否则返回发送者自身。 */
    public Player targetOrSelf(CommandSender s, String[] args, int idx, String permKey) {
        if (args.length > idx) {
            Player t = online(s, args[idx]);
            if (!t.equals(s) && !s.hasPermission("lbr.essentials." + permKey + ".others")) {
                msg(s, "no-permission-others");
                throw new Cmd.Stop();
            }
            return t;
        }
        if (s instanceof Player p) return p;
        throw new Cmd.Usage();
    }

    public Player online(CommandSender s, String name) {
        Player p = Cmd.findOnline(name);
        if (p == null || (s instanceof Player viewer && !viewer.equals(p) && !vanish.canSee(viewer, p))) {
            msg(s, "player-not-found", "player", name);
            throw new Cmd.Stop();
        }
        return p;
    }

    public int parseInt(CommandSender s, String v) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            msg(s, "invalid-number", "value", v);
            throw new Cmd.Stop();
        }
    }

    public double parseDouble(CommandSender s, String v) {
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            msg(s, "invalid-number", "value", v);
            throw new Cmd.Stop();
        }
    }

    public String actor(CommandSender s) {
        return s instanceof ConsoleCommandSender ? "console" : s.getName();
    }

    /** 对他人执行时同时通知目标。 */
    public void notifyTarget(CommandSender s, Player target, String key, Object... ph) {
        if (!target.equals(s)) msg(target, key, ph);
    }

    // ---------------- 访问器 ----------------

    public UserManager users() { return users; }

    public AfkManager afk() { return afk; }

    public VanishManager vanish() { return vanish; }

    public KitManager kits() { return kits; }

    public WorthManager worth() { return worth; }

    public UserData data(Player p) {
        return users.get(p.getUniqueId());
    }

    public com.lunarboreal.api.message.MessageService.Rich onOff(boolean b) {
        return com.lunarboreal.api.message.MessageService.rich(raw(b ? "state.on" : "state.off"));
    }
}
