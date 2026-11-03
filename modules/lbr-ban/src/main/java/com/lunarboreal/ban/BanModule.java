package com.lunarboreal.ban;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.service.BanService;
import com.lunarboreal.api.service.BanService.Punishment;
import com.lunarboreal.api.service.BanService.Type;
import com.lunarboreal.api.util.Durations;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.permissions.PermissionDefault;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 封禁系统模块：提供 {@link BanService}。
 */
public final class BanModule extends LbrModule {

    static final Pattern IP_PATTERN = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+:[0-9a-fA-F:]*$");

    private PunishmentStore store;
    private volatile DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private volatile Set<String> mutedCommands = Set.of();

    /** 解析后的处罚目标。uuid 可能为 null（纯 IP）。 */
    record Target(UUID uuid, String name, String ip, Player online) {}

    /** 原因模板。duration 为 null 表示不指定。 */
    record ReasonTemplate(String id, String reason, Long duration, boolean hasDuration) {}

    @Override
    public void onEnable() {
        reloadSettings();
        store = new PunishmentStore(this, api().database());
        store.init();
        provide(BanService.class, store.asService());

        registerPermissions();
        BanCommands.registerAll(this);
        registerListener(new BanListener(this));
        if (api().isPaper()) {
            registerListener(new PaperChatListener(this));
        } else {
            registerListener(new BukkitChatListener(this));
        }
        // 每 30 秒清理过期处罚
        final PunishmentStore s = store;
        runAsyncTimer(s::sweepExpired, 20L * 30, 20L * 30);
        // 多服共享数据库：定时增量同步其它服的新处罚 / 撤销
        long syncTicks = Math.max(5, config().getLong("multi-server.sync-seconds", 15)) * 20L;
        runAsyncTimer(() -> {
            if (!multiServer()) return;
            List<Punishment> added = s.syncRemote();
            if (!added.isEmpty()) onMain(() -> added.forEach(this::enforceRemote));
        }, syncTicks, syncTicks);

        placeholder((player, key) -> {
            if (player == null) return null;
            UUID u = player.getUniqueId();
            return switch (key.toLowerCase(Locale.ROOT)) {
                case "muted" -> String.valueOf(store.activeMute(u).isPresent());
                case "banned" -> String.valueOf(store.activeBan(u).isPresent());
                case "warns" -> String.valueOf(store.warnCount(u));
                case "mute_reason" -> store.activeMute(u).map(Punishment::reason).orElse("");
                case "mute_remaining" -> store.activeMute(u).map(this::remaining).orElse("");
                case "mute_expires" -> store.activeMute(u).map(p -> formatExpires(p.expiresAt())).orElse("");
                default -> null;
            };
        });

        new BanWeb(this).register();
    }

    @Override
    public void onDisable() {
        // 等待有序写队列中的数据库写入全部完成
        if (store != null) store.close();
        store = null;
    }

    @Override
    public void onReload() {
        reloadConfig();
        reloadSettings();
    }

    void reloadSettings() {
        try {
            dateFormat = DateTimeFormatter.ofPattern(config().getString("date-format", "yyyy-MM-dd HH:mm"));
        } catch (IllegalArgumentException e) {
            dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        }
        Set<String> cmds = new HashSet<>();
        for (String c : config().getStringList("muted-commands")) cmds.add(c.toLowerCase(Locale.ROOT).replace("/", "").trim());
        mutedCommands = cmds;
    }

    private void registerPermissions() {
        String[][] perms = {
                {"lbr.ban.ban", "永久封禁"}, {"lbr.ban.tempban", "临时封禁"}, {"lbr.ban.ipban", "永久 IP 封禁"},
                {"lbr.ban.tempipban", "临时 IP 封禁"}, {"lbr.ban.unban", "解除封禁"}, {"lbr.ban.unbanip", "解除 IP 封禁"},
                {"lbr.ban.mute", "永久禁言"}, {"lbr.ban.tempmute", "临时禁言"}, {"lbr.ban.unmute", "解除禁言"},
                {"lbr.ban.warn", "警告"}, {"lbr.ban.unwarn", "撤销警告"}, {"lbr.ban.kick", "踢出"},
                {"lbr.ban.history.others", "查看他人处罚历史"}, {"lbr.ban.banlist", "查看封禁列表"},
                {"lbr.ban.checkban", "查询封禁状态"}, {"lbr.ban.reload", "重载封禁配置"},
                {"lbr.ban.notify", "接收静默处罚通知"}, {"lbr.ban.exempt", "免疫处罚"},
                {"lbr.ban.limit.bypass", "无视时长上限"}, {"lbr.ban.silent", "使用 -s 静默参数"}
        };
        for (String[] p : perms) registerPermission(p[0], PermissionDefault.OP, p[1]);
        registerPermission("lbr.ban.history", PermissionDefault.TRUE, "查看自己的处罚历史");
    }

    // ======================================================================
    // 访问器
    // ======================================================================

    PunishmentStore store() { return store; }

    String serverName() { return config().getString("server-name", "default"); }

    boolean multiServer() { return config().getBoolean("multi-server.enabled", false); }

    /**
     * 目标是否豁免处罚。在线玩家检查 lbr.ban.exempt；离线玩家无法查询权限插件，
     * 退而检查是否为 OP（读取 ops.json，不联网）。IP 目标检查同 IP 的在线玩家。
     */
    boolean isExempt(Target t) {
        if (!config().getBoolean("exempt-enabled", true)) return false;
        if (t.online() != null) return t.online().hasPermission("lbr.ban.exempt");
        if (t.uuid() != null) {
            try {
                if (Bukkit.getOfflinePlayer(t.uuid()).isOp()) return true;
            } catch (Exception ignored) {}
        }
        if (t.ip() != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (t.ip().equals(ipOf(p)) && p.hasPermission("lbr.ban.exempt")) return true;
            }
        }
        return false;
    }

    /** 其它服写入的处罚同步到本服后（主线程）：踢出 / 提示，不广播。 */
    void enforceRemote(Punishment p) {
        switch (p.type()) {
            case BAN -> {
                Player pl = p.target() == null ? null : Bukkit.getPlayer(p.target());
                if (pl != null) pl.kick(screen(p), PlayerKickEvent.Cause.BANNED);
            }
            case IPBAN -> {
                for (Player pl : List.copyOf(Bukkit.getOnlinePlayers())) {
                    if (p.ip() != null && p.ip().equals(ipOf(pl))) pl.kick(screen(p), PlayerKickEvent.Cause.IP_BANNED);
                }
            }
            case MUTE -> {
                Player pl = p.target() == null ? null : Bukkit.getPlayer(p.target());
                if (pl != null) msg(pl, p.expiresAt() == null ? "notify.muted" : "notify.tempmuted", punishmentArgs(p));
            }
            default -> {}
        }
    }

    boolean isMutedCommand(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        int colon = l.indexOf(':');
        if (colon >= 0) l = l.substring(colon + 1);
        return mutedCommands.contains(l);
    }

    String formatDate(long millis) {
        return dateFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    String formatExpires(Long expires) {
        return expires == null ? raw("words.permanent") : formatDate(expires);
    }

    String remaining(Punishment p) {
        if (p.expiresAt() == null) return raw("words.permanent");
        return Durations.format(Math.max(0, p.expiresAt() - System.currentTimeMillis()));
    }

    String typeName(Type type, Long expires) {
        String base = type.name().toLowerCase(Locale.ROOT);
        return raw("types." + base + ((expires != null && (type == Type.BAN || type == Type.IPBAN || type == Type.MUTE)) ? "-temp" : ""));
    }

    /** 处罚相关的占位符参数（key,value 交替）。 */
    Object[] punishmentArgs(Punishment p) {
        return new Object[]{
                "id", p.id(),
                "player", p.targetName() == null ? (p.ip() == null ? "?" : p.ip()) : p.targetName(),
                "ip", p.ip() == null ? "-" : p.ip(),
                "reason", p.reason() == null ? "" : p.reason(),
                "operator", p.operator() == null ? "console" : p.operator(),
                "type", typeName(p.type(), p.expiresAt()),
                "date", formatDate(p.createdAt()),
                "expires", formatExpires(p.expiresAt()),
                "remaining", remaining(p),
                "duration", p.expiresAt() == null ? raw("words.permanent") : Durations.format(p.expiresAt() - p.createdAt()),
                "appeal", config().getString("appeal-url", ""),
                "server", serverName()
        };
    }

    /** 踢出/拒绝登录界面。 */
    Component screen(Punishment p) {
        String key = switch (p.type()) {
            case BAN -> p.expiresAt() == null ? "screen.ban" : "screen.tempban";
            case IPBAN -> p.expiresAt() == null ? "screen.ipban" : "screen.tempipban";
            case KICK -> "screen.kick";
            default -> "screen.kick";
        };
        return api().messages().component(id() + "." + key, punishmentArgs(p));
    }

    // ======================================================================
    // 原因模板 / 时长上限
    // ======================================================================

    Optional<ReasonTemplate> template(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String key = token.startsWith("#") ? token.substring(1) : token;
        ConfigurationSection sec = config().getConfigurationSection("reasons");
        if (sec == null) return Optional.empty();
        for (String k : sec.getKeys(false)) {
            if (!k.equalsIgnoreCase(key)) continue;
            String reason = sec.getString(k + ".reason", k);
            String dur = sec.getString(k + ".duration", "");
            Long duration = null;
            boolean has = false;
            if (dur != null && !dur.isBlank()) {
                try { duration = Durations.parse(dur); has = true; } catch (IllegalArgumentException ignored) {}
            }
            return Optional.of(new ReasonTemplate(k, reason, duration, has));
        }
        return Optional.empty();
    }

    List<String> templateIds() {
        ConfigurationSection sec = config().getConfigurationSection("reasons");
        return sec == null ? List.of() : new ArrayList<>(sec.getKeys(false));
    }

    /**
     * @return 该执行者在此命令下允许的最大时长（毫秒），null 表示不限
     */
    Long durationLimit(CommandSender sender, String command) {
        if (!config().getBoolean("duration-limits.enabled", false)) return null;
        if (sender instanceof ConsoleCommandSender || sender.hasPermission("lbr.ban.limit.bypass")) return null;
        Long best = null;
        for (String step : config().getStringList("duration-limits.steps")) {
            if (!sender.hasPermission("lbr.ban." + command + ".max." + step.toLowerCase(Locale.ROOT))) continue;
            try {
                Long v = Durations.parse(step);
                if (v == null) return null;
                if (best == null || v > best) best = v;
            } catch (IllegalArgumentException ignored) {}
        }
        if (best != null) return best;
        try {
            return Durations.parse(config().getString("duration-limits.default", "1d"));
        } catch (IllegalArgumentException e) {
            return 86_400_000L;
        }
    }

    // ======================================================================
    // 目标解析
    // ======================================================================

    /** 解析玩家名 / UUID / IP（allowIp 时）。应在主线程调用。 */
    Optional<Target> resolveTarget(String input, boolean allowIp) {
        if (input == null || input.isBlank()) return Optional.empty();
        if (allowIp && IP_PATTERN.matcher(input).matches()) {
            return Optional.of(new Target(null, null, input, null));
        }
        Player online = Bukkit.getPlayerExact(input);
        if (online == null) {
            try {
                Player byUuid = Bukkit.getPlayer(UUID.fromString(input));
                if (byUuid != null) online = byUuid;
            } catch (IllegalArgumentException ignored) {}
        }
        if (online != null) return Optional.of(new Target(online.getUniqueId(), online.getName(), ipOf(online), online));
        var cache = api().players();
        var rec = cache.getByName(input);
        if (rec.isEmpty()) {
            try { rec = cache.get(UUID.fromString(input)); } catch (IllegalArgumentException ignored) {}
        }
        return rec.map(r -> new Target(r.uuid(), r.name(), r.lastIp(), null));
    }

    static String ipOf(Player p) {
        InetSocketAddress a = p.getAddress();
        return a == null || a.getAddress() == null ? null : a.getAddress().getHostAddress();
    }

    // ======================================================================
    // 执行处罚
    // ======================================================================

    /** 其它模块通过 BanService 发起的处罚。 */
    Punishment punishFromApi(Type type, UUID target, String name, String ip, String reason, String operator, Long duration) {
        if (target != null && name == null) name = api().players().nameOf(target);
        if (type == Type.IPBAN && ip == null && target != null) {
            Player online = Bukkit.getPlayer(target);
            ip = online != null ? ipOf(online) : api().players().get(target).map(r -> r.lastIp()).orElse(null);
            if (ip == null) throw new IllegalArgumentException("无法确定目标 IP");
        }
        boolean silent = !config().getBoolean("broadcast.api", true);
        return execute(type, new Target(target, name, ip, null), reason, operator == null ? "API" : operator, duration, silent);
    }

    /**
     * 统一处罚入口（任意线程）：写入、执行（踢出/提示）、广播、审计、Web 推送、警告阶梯。
     */
    Punishment execute(Type type, Target t, String reason, String operator, Long duration, boolean silent) {
        if (reason == null || reason.isBlank()) reason = config().getString("default-reason", "违反服务器规则");
        Punishment p = store.create(type, t.uuid(), t.name(), t.ip(), reason, operator, duration);
        final String fReason = reason;
        onMain(() -> enforce(p, silent));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", p.id());
        payload.put("type", p.type().name());
        payload.put("uuid", p.target() == null ? null : p.target().toString());
        payload.put("name", p.targetName());
        payload.put("ip", p.ip());
        payload.put("reason", fReason);
        payload.put("operator", operator);
        payload.put("created", p.createdAt());
        payload.put("expires", p.expiresAt());
        payload.put("silent", silent);
        try { api().web().broadcast("ban.punish", payload); } catch (Exception ignored) {}

        audit(operator, type.name().toLowerCase(Locale.ROOT),
                "#" + p.id() + " " + (t.name() != null ? t.name() : t.ip()) + " 原因=" + fReason
                        + " 时长=" + (duration == null ? "永久" : Durations.format(duration)) + (silent ? " (静默)" : ""));

        if (type == Type.WARN && t.uuid() != null) checkWarnLadder(t, operator);
        return p;
    }

    private void checkWarnLadder(Target t, String operator) {
        int count = store.warnCount(t.uuid());
        String action = config().getString("warn.actions." + count);
        if (action == null || action.isBlank()) return;
        String[] parts = action.trim().split("\\s+", 3);
        String kind = parts[0].toLowerCase(Locale.ROOT);
        Long duration = null;
        String reason = null;
        if (kind.startsWith("temp") && parts.length > 1) {
            try { duration = Durations.parse(parts[1]); } catch (IllegalArgumentException ignored) {}
            if (parts.length > 2) reason = parts[2];
        } else {
            String[] two = action.trim().split("\\s+", 2);
            if (two.length > 1) reason = two[1];
        }
        if (reason == null || reason.isBlank()) reason = raw("warn.auto-reason", "count", count);
        Type type = switch (kind) {
            case "kick" -> Type.KICK;
            case "mute", "tempmute" -> Type.MUTE;
            case "ban", "tempban" -> Type.BAN;
            default -> null;
        };
        if (type == null) {
            logger().warning("无效的警告阶梯动作: " + action);
            return;
        }
        execute(type, t, reason, "AutoWarn(" + operator + ")", duration, false);
        if (config().getBoolean("warn.reset-after-action", false)) {
            for (Punishment w : store.activeWarns(t.uuid())) store.revoke(w.id(), "AutoWarn");
        }
    }

    /** 主线程：踢出 / 通知 / 广播。 */
    private void enforce(Punishment p, boolean silent) {
        switch (p.type()) {
            case BAN, KICK -> {
                Player pl = p.target() == null ? null : Bukkit.getPlayer(p.target());
                if (pl != null) pl.kick(screen(p), p.type() == Type.BAN ? PlayerKickEvent.Cause.BANNED : PlayerKickEvent.Cause.KICK_COMMAND);
            }
            case IPBAN -> {
                for (Player pl : List.copyOf(Bukkit.getOnlinePlayers())) {
                    if (p.ip() != null && p.ip().equals(ipOf(pl)) || (p.target() != null && p.target().equals(pl.getUniqueId()))) {
                        pl.kick(screen(p), PlayerKickEvent.Cause.IP_BANNED);
                    }
                }
            }
            case MUTE -> {
                Player pl = p.target() == null ? null : Bukkit.getPlayer(p.target());
                if (pl != null) msg(pl, p.expiresAt() == null ? "notify.muted" : "notify.tempmuted", punishmentArgs(p));
            }
            case WARN -> {
                Player pl = p.target() == null ? null : Bukkit.getPlayer(p.target());
                if (pl != null) msg(pl, "notify.warned", append(punishmentArgs(p), "count", store.warnCount(p.target())));
            }
        }
        broadcastPunishment(p, silent);
    }

    private void broadcastPunishment(Punishment p, boolean silent) {
        String key = "broadcast." + p.type().name().toLowerCase(Locale.ROOT)
                + ((p.expiresAt() != null && p.type() != Type.WARN && p.type() != Type.KICK) ? "-temp" : "");
        Object[] args = punishmentArgs(p);
        boolean global = !silent && config().getBoolean("broadcast.enabled", true)
                && config().getStringList("broadcast.types").stream().anyMatch(s -> s.equalsIgnoreCase(p.type().name()));
        if (global) {
            for (Player pl : Bukkit.getOnlinePlayers()) msg(pl, key, args);
        } else {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.hasPermission("lbr.ban.notify")) msg(pl, "broadcast.silent-prefix", append(args, "message",
                        com.lunarboreal.api.message.MessageService.rich(raw(key, args))));
            }
        }
        msg(Bukkit.getConsoleSender(), key, args);
    }

    /** 撤销后的通知（主线程）。 */
    void notifyRevoke(String key, CommandSender sender, boolean silent, Object... args) {
        boolean global = !silent && config().getBoolean("broadcast.enabled", true);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (global || pl.hasPermission("lbr.ban.notify")) msg(pl, key, args);
        }
        if (!(sender instanceof Player) || !(global || sender.hasPermission("lbr.ban.notify"))) msg(sender, key, args);
    }

    static Object[] append(Object[] base, Object... more) {
        Object[] out = new Object[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }

    void onMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin(), () -> { if (isEnabled()) r.run(); });
    }
}
