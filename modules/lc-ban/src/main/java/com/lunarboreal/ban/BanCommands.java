package com.lunarboreal.ban;

import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.api.service.BanService.Punishment;
import com.lunarboreal.api.service.BanService.Type;
import com.lunarboreal.api.util.Durations;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 全部封禁相关命令。 */
final class BanCommands {

    private static final List<String> DURATION_HINTS = List.of("30m", "1h", "6h", "1d", "3d", "7d", "14d", "30d", "perm");
    private static final int PAGE_SIZE = 8;

    private BanCommands() {}

    static void registerAll(BanModule m) {
        m.registerCommand(new PunishCommand(m, "ban", "lc.ban.ban", Type.BAN, false, "lc.ban.ban").override(true).usage("/ban <玩家> [原因] [-s]"));
        m.registerCommand(new PunishCommand(m, "tempban", "lc.ban.tempban", Type.BAN, true, "lc.ban.tempban").usage("/tempban <玩家> <时长> [原因] [-s]"));
        m.registerCommand(new PunishCommand(m, "ipban", "lc.ban.ipban", Type.IPBAN, false, "lc.ban.ipban", "ban-ip", "banip").override(true).usage("/ipban <玩家|IP> [原因] [-s]"));
        m.registerCommand(new PunishCommand(m, "tempipban", "lc.ban.tempipban", Type.IPBAN, true, "lc.ban.tempipban").usage("/tempipban <玩家|IP> <时长> [原因] [-s]"));
        m.registerCommand(new PunishCommand(m, "mute", "lc.ban.mute", Type.MUTE, false, "lc.ban.mute").usage("/mute <玩家> [原因] [-s]"));
        m.registerCommand(new PunishCommand(m, "tempmute", "lc.ban.tempmute", Type.MUTE, true, "lc.ban.tempmute").usage("/tempmute <玩家> <时长> [原因] [-s]"));
        m.registerCommand(new PunishCommand(m, "warn", "lc.ban.warn", Type.WARN, false, "lc.ban.warn").usage("/warn <玩家> [原因] [-s]"));
        m.registerCommand(new PunishCommand(m, "kick", "lc.ban.kick", Type.KICK, false, "lc.ban.kick").override(true).usage("/kick <玩家> [原因] [-s]"));
        m.registerCommand(new UnbanCommand(m).override(true).usage("/unban <玩家> [-s]"));
        m.registerCommand(new UnbanIpCommand(m).override(true).usage("/unbanip <IP|玩家> [-s]"));
        m.registerCommand(new UnmuteCommand(m).usage("/unmute <玩家> [-s]"));
        m.registerCommand(new UnwarnCommand(m).usage("/unwarn <玩家> [ID|all]"));
        m.registerCommand(new HistoryCommand(m).usage("/history [玩家] [页码]"));
        m.registerCommand(new BanListCommand(m).override(true).usage("/banlist [页码]"));
        m.registerCommand(new CheckBanCommand(m).usage("/checkban <玩家>"));
        m.registerCommand(new ReloadCommand(m).usage("/lcbanreload"));
    }

    static String operatorName(CommandSender sender) {
        return sender instanceof ConsoleCommandSender ? "Console" : sender.getName();
    }

    record Parsed(List<String> args, boolean silent, boolean anticheat, String check) {}

    private static Parsed parse(String[] args) {
        return parse(args, false);
    }

    static Parsed parse(String[] args, boolean console) {
        List<String> list = new ArrayList<>();
        boolean silent = false;
        boolean anticheat = false;
        String check = null;
        for (String a : args) {
            if (a.equalsIgnoreCase("-s")) silent = true;
            else if (console && a.equalsIgnoreCase("--source=anticheat")) anticheat = true;
            else if (console && a.startsWith("--check=")) check = a.substring("--check=".length());
            else list.add(a);
        }
        return new Parsed(List.copyOf(list), silent, anticheat, check);
    }

    private static boolean checkSilent(BanModule m, CommandSender sender, boolean silent) {
        if (silent && !sender.hasPermission("lc.ban.silent")) {
            m.msg(sender, "error.no-silent");
            return false;
        }
        return true;
    }

    // ======================================================================

    static final class PunishCommand extends LcCommand {
        private final BanModule m;
        private final Type type;
        private final boolean temp;

        PunishCommand(BanModule m, String name, String perm, Type type, boolean temp, String ignored, String... aliases) {
            super(name, perm, aliases);
            this.m = m;
            this.type = type;
            this.temp = temp;
            description(m.raw("command-desc." + name));
        }

        @Override
        public void execute(CommandSender sender, String label, String[] rawArgs) {
            Parsed p = parse(rawArgs, sender instanceof ConsoleCommandSender);
            List<String> args = p.args();
            if (args.isEmpty() || (temp && args.size() < 2)) {
                m.msg(sender, "error.usage", "usage", usage());
                return;
            }
            if (!checkSilent(m, sender, p.silent())) return;
            Optional<BanModule.Target> ot = m.resolveTarget(args.get(0), type == Type.IPBAN);
            if (ot.isEmpty()) {
                m.msg(sender, "error.player-not-found", "player", args.get(0));
                return;
            }
            BanModule.Target t = ot.get();
            if (type == Type.IPBAN && (t.ip() == null || t.ip().isBlank())) {
                m.msg(sender, "error.no-ip", "player", args.get(0));
                return;
            }
            if (type == Type.KICK && t.online() == null) {
                m.msg(sender, "error.not-online", "player", args.get(0));
                return;
            }
            if (!(sender instanceof ConsoleCommandSender) && m.isExempt(t)) {
                m.msg(sender, "error.exempt", "player", t.name() != null ? t.name() : t.ip());
                return;
            }
            if (t.uuid() != null && sender instanceof Player sp && sp.getUniqueId().equals(t.uuid())) {
                m.msg(sender, "error.self");
                return;
            }

            int reasonStart = 1;
            Long duration = null;
            boolean hasDuration = false;
            if (temp) {
                try {
                    duration = Durations.parse(args.get(1));
                    hasDuration = true;
                } catch (IllegalArgumentException e) {
                    m.msg(sender, "error.invalid-duration", "input", args.get(1));
                    return;
                }
                reasonStart = 2;
            }
            String reason = String.join(" ", args.subList(Math.min(reasonStart, args.size()), args.size())).trim();
            if (p.anticheat()) reason = m.anticheatReason(p.check(), reason);
            if (!reason.isEmpty() && !reason.contains(" ")) {
                Optional<BanModule.ReasonTemplate> tpl = m.template(reason);
                if (tpl.isPresent()) {
                    reason = tpl.get().reason();
                    if (!hasDuration && tpl.get().hasDuration() && type != Type.KICK && type != Type.WARN) {
                        duration = tpl.get().duration();
                        hasDuration = true;
                    }
                }
            }
            if (type == Type.WARN) {
                String exp = m.config().getString("warn.expire", "");
                try { duration = exp == null || exp.isBlank() ? null : Durations.parse(exp); } catch (IllegalArgumentException e) { duration = null; }
            } else if (hasDuration && duration != null) {
                String limitCmd = switch (type) {
                    case BAN -> "tempban";
                    case IPBAN -> "tempipban";
                    case MUTE -> "tempmute";
                    default -> name();
                };
                Long limit = m.durationLimit(sender, limitCmd);
                if (limit != null && duration > limit) {
                    m.msg(sender, "error.duration-limit", "max", Durations.format(limit));
                    return;
                }
            }
            if (type == Type.BAN && t.uuid() != null && m.store().activeBan(t.uuid()).isPresent()) {
                m.msg(sender, "notice.replaced", "player", t.name());
            }
            if (type == Type.MUTE && t.uuid() != null && m.store().activeMute(t.uuid()).isPresent()) {
                m.msg(sender, "notice.replaced", "player", t.name());
            }
            Punishment pun = m.execute(type, t, reason, p.anticheat() ? "Anticheat" : operatorName(sender), duration, p.silent());
            m.msg(sender, "command.done", "id", pun.id(), "type", m.typeName(pun.type(), pun.expiresAt()),
                    "player", t.name() != null ? t.name() : t.ip());
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) return onlinePlayers(args[0]);
            if (temp && args.length == 2) return filter(DURATION_HINTS, args[1]);
            List<String> opts = new ArrayList<>(m.templateIds());
            opts.add("-s");
            return filter(opts, args[args.length - 1]);
        }
    }

    // ======================================================================

    static final class UnbanCommand extends LcCommand {
        private final BanModule m;
        UnbanCommand(BanModule m) { super("unban", "lc.ban.unban", "pardon"); this.m = m; description(m.raw("command-desc.unban")); }

        @Override
        public void execute(CommandSender sender, String label, String[] rawArgs) {
            Parsed p = parse(rawArgs);
            if (p.args().isEmpty()) { m.msg(sender, "error.usage", "usage", usage()); return; }
            if (!checkSilent(m, sender, p.silent())) return;
            Optional<BanModule.Target> t = m.resolveTarget(p.args().get(0), false);
            if (t.isEmpty()) { m.msg(sender, "error.player-not-found", "player", p.args().get(0)); return; }
            Optional<Punishment> ban = m.store().activeBan(t.get().uuid());
            if (ban.isEmpty()) { m.msg(sender, "error.not-banned", "player", t.get().name()); return; }
            m.store().revoke(ban.get().id(), operatorName(sender));
            m.audit(operatorName(sender), "unban", "#" + ban.get().id() + " " + t.get().name());
            m.notifyRevoke("broadcast.unban", sender, p.silent(), "player", t.get().name(), "operator", operatorName(sender));
        }
    }

    static final class UnbanIpCommand extends LcCommand {
        private final BanModule m;
        UnbanIpCommand(BanModule m) { super("unbanip", "lc.ban.unbanip", "pardon-ip", "unipban"); this.m = m; description(m.raw("command-desc.unbanip")); }

        @Override
        public void execute(CommandSender sender, String label, String[] rawArgs) {
            Parsed p = parse(rawArgs);
            if (p.args().isEmpty()) { m.msg(sender, "error.usage", "usage", usage()); return; }
            if (!checkSilent(m, sender, p.silent())) return;
            Optional<BanModule.Target> t = m.resolveTarget(p.args().get(0), true);
            if (t.isEmpty() || t.get().ip() == null) { m.msg(sender, "error.player-not-found", "player", p.args().get(0)); return; }
            String ip = t.get().ip();
            Optional<Punishment> ban = m.store().activeIpBan(ip);
            if (ban.isEmpty()) { m.msg(sender, "error.not-ipbanned", "ip", ip); return; }
            m.store().revoke(ban.get().id(), operatorName(sender));
            m.audit(operatorName(sender), "unbanip", "#" + ban.get().id() + " " + ip);
            m.notifyRevoke("broadcast.unbanip", sender, p.silent(), "ip", ip,
                    "player", t.get().name() == null ? ip : t.get().name(), "operator", operatorName(sender));
        }
    }

    static final class UnmuteCommand extends LcCommand {
        private final BanModule m;
        UnmuteCommand(BanModule m) { super("unmute", "lc.ban.unmute"); this.m = m; description(m.raw("command-desc.unmute")); }

        @Override
        public void execute(CommandSender sender, String label, String[] rawArgs) {
            Parsed p = parse(rawArgs);
            if (p.args().isEmpty()) { m.msg(sender, "error.usage", "usage", usage()); return; }
            if (!checkSilent(m, sender, p.silent())) return;
            Optional<BanModule.Target> t = m.resolveTarget(p.args().get(0), false);
            if (t.isEmpty()) { m.msg(sender, "error.player-not-found", "player", p.args().get(0)); return; }
            Optional<Punishment> mute = m.store().activeMute(t.get().uuid());
            if (mute.isEmpty()) { m.msg(sender, "error.not-muted", "player", t.get().name()); return; }
            m.store().revoke(mute.get().id(), operatorName(sender));
            m.audit(operatorName(sender), "unmute", "#" + mute.get().id() + " " + t.get().name());
            m.notifyRevoke("broadcast.unmute", sender, p.silent(), "player", t.get().name(), "operator", operatorName(sender));
            if (t.get().online() != null) m.msg(t.get().online(), "notify.unmuted");
        }
    }

    static final class UnwarnCommand extends LcCommand {
        private final BanModule m;
        UnwarnCommand(BanModule m) { super("unwarn", "lc.ban.unwarn", "delwarn"); this.m = m; description(m.raw("command-desc.unwarn")); }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            if (args.length == 0) { m.msg(sender, "error.usage", "usage", usage()); return; }
            Optional<BanModule.Target> t = m.resolveTarget(args[0], false);
            if (t.isEmpty()) { m.msg(sender, "error.player-not-found", "player", args[0]); return; }
            List<Punishment> warns = new ArrayList<>(m.store().activeWarns(t.get().uuid()));
            if (warns.isEmpty()) { m.msg(sender, "error.no-warns", "player", t.get().name()); return; }
            warns.sort(Comparator.comparingLong(Punishment::id));
            List<Punishment> toRevoke;
            if (args.length > 1 && args[1].equalsIgnoreCase("all")) {
                toRevoke = warns;
            } else if (args.length > 1) {
                long id;
                try { id = Long.parseLong(args[1].replace("#", "")); } catch (NumberFormatException e) { m.msg(sender, "error.usage", "usage", usage()); return; }
                toRevoke = warns.stream().filter(w -> w.id() == id).toList();
                if (toRevoke.isEmpty()) { m.msg(sender, "error.no-warns", "player", t.get().name()); return; }
            } else {
                toRevoke = List.of(warns.get(warns.size() - 1));
            }
            for (Punishment w : toRevoke) m.store().revoke(w.id(), operatorName(sender));
            m.audit(operatorName(sender), "unwarn", t.get().name() + " x" + toRevoke.size());
            m.msg(sender, "command.unwarned", "player", t.get().name(), "count", toRevoke.size(),
                    "remaining", m.store().warnCount(t.get().uuid()));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 2) return filter(List.of("all"), args[1]);
            return super.tabComplete(sender, alias, args);
        }
    }

    // ======================================================================

    static final class HistoryCommand extends LcCommand {
        private final BanModule m;
        HistoryCommand(BanModule m) { super("history", "lc.ban.history", "punishments"); this.m = m; description(m.raw("command-desc.history")); }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            int page = 1;
            String targetName = null;
            for (String a : args) {
                if (a.matches("\\d{1,4}")) page = Math.max(1, Integer.parseInt(a));
                else targetName = a;
            }
            if (targetName == null) {
                if (!(sender instanceof Player)) { m.msg(sender, "error.usage", "usage", usage()); return; }
                targetName = sender.getName();
            }
            boolean self = sender instanceof Player && sender.getName().equalsIgnoreCase(targetName);
            if (!self && !sender.hasPermission("lc.ban.history.others")) { m.msg(sender, "error.no-permission"); return; }
            Optional<BanModule.Target> t = m.resolveTarget(targetName, false);
            if (t.isEmpty() || t.get().uuid() == null) { m.msg(sender, "error.player-not-found", "player", targetName); return; }
            final int fPage = page;
            final String name = t.get().name();
            m.store().historyAsync(t.get().uuid()).whenComplete((list, err) -> m.onMain(() -> {
                if (err != null) { m.msg(sender, "error.database"); return; }
                if (list.isEmpty()) { m.msg(sender, "history.empty", "player", name); return; }
                int pages = (list.size() + PAGE_SIZE - 1) / PAGE_SIZE;
                int pg = Math.min(fPage, pages);
                m.msg(sender, "history.header", "player", name, "page", pg, "pages", pages, "total", list.size());
                for (Punishment p : list.subList((pg - 1) * PAGE_SIZE, Math.min(list.size(), pg * PAGE_SIZE))) {
                    String state = p.active() && !p.isExpired() ? m.raw("words.active")
                            : p.revokedBy() != null ? m.raw("words.revoked", "operator", p.revokedBy()) : m.raw("words.inactive");
                    m.msg(sender, "history.entry", BanModule.append(m.punishmentArgs(p), "state",
                            com.lunarboreal.api.message.MessageService.rich(state)));
                }
            }));
        }
    }

    static final class BanListCommand extends LcCommand {
        private final BanModule m;
        BanListCommand(BanModule m) { super("banlist", "lc.ban.banlist", "bans"); this.m = m; description(m.raw("command-desc.banlist")); }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            int page = 1;
            if (args.length > 0) try { page = Math.max(1, Integer.parseInt(args[0])); } catch (NumberFormatException ignored) {}
            List<Punishment> all = new ArrayList<>();
            for (Punishment p : m.store().activeBans()) if (!p.isExpired()) all.add(p);
            for (Punishment p : m.store().activeIpBans()) if (!p.isExpired()) all.add(p);
            all.sort(Comparator.comparingLong(Punishment::createdAt).reversed());
            if (all.isEmpty()) { m.msg(sender, "banlist.empty"); return; }
            int pages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            page = Math.min(page, pages);
            m.msg(sender, "banlist.header", "page", page, "pages", pages, "total", all.size());
            for (Punishment p : all.subList((page - 1) * PAGE_SIZE, Math.min(all.size(), page * PAGE_SIZE))) {
                m.msg(sender, "banlist.entry", m.punishmentArgs(p));
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) { return List.of(); }
    }

    static final class CheckBanCommand extends LcCommand {
        private final BanModule m;
        CheckBanCommand(BanModule m) { super("checkban", "lc.ban.checkban", "baninfo", "checkmute"); this.m = m; description(m.raw("command-desc.checkban")); }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            if (args.length == 0) { m.msg(sender, "error.usage", "usage", usage()); return; }
            Optional<BanModule.Target> ot = m.resolveTarget(args[0], true);
            if (ot.isEmpty()) { m.msg(sender, "error.player-not-found", "player", args[0]); return; }
            BanModule.Target t = ot.get();
            String display = t.name() != null ? t.name() : t.ip();
            m.msg(sender, "check.header", "player", display, "ip", t.ip() == null ? "-" : t.ip());
            boolean any = false;
            if (t.uuid() != null) {
                Optional<Punishment> ban = m.store().activeBan(t.uuid());
                if (ban.isPresent()) { any = true; m.msg(sender, "check.ban", m.punishmentArgs(ban.get())); }
                Optional<Punishment> mute = m.store().activeMute(t.uuid());
                if (mute.isPresent()) { any = true; m.msg(sender, "check.mute", m.punishmentArgs(mute.get())); }
            }
            if (t.ip() != null) {
                Optional<Punishment> ipban = m.store().activeIpBan(t.ip());
                if (ipban.isPresent()) { any = true; m.msg(sender, "check.ipban", m.punishmentArgs(ipban.get())); }
            }
            if (!any) m.msg(sender, "check.clean");
            if (t.uuid() != null) m.msg(sender, "check.warns", "count", m.store().warnCount(t.uuid()));
        }
    }

    static final class ReloadCommand extends LcCommand {
        private final BanModule m;
        ReloadCommand(BanModule m) { super("lcbanreload", "lc.ban.reload", "banreload", "lcbanreload"); this.m = m; description(m.raw("command-desc.reload")); }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            m.onReload();
            m.audit(operatorName(sender), "reload", "config.yml");
            m.msg(sender, "command.reloaded");
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) { return List.of(); }
    }
}
