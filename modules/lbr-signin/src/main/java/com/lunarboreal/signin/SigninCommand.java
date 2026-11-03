package com.lunarboreal.signin;

import com.lunarboreal.api.command.LbrCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /signin [sign|top [streak|total] [页]|admin reset <玩家>] */
public final class SigninCommand extends LbrCommand {

    private final SigninModule m;

    public SigninCommand(SigninModule m) {
        super("signin", "lbr.signin.use", "qd", "checkin");
        this.m = m;
        description("每日签到");
        usage("/signin [sign|top|admin reset <玩家>]");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (!(sender instanceof Player p)) {
                    m.msg(sender, "usage");
                    return;
                }
                if (m.settings().guiEnabled) SigninGui.open(m, p);
                else m.withData(p, d -> m.signToday(p, d));
            }
            case "sign", "now" -> {
                if (!(sender instanceof Player p)) {
                    m.msg(sender, "usage");
                    return;
                }
                m.withData(p, d -> m.signToday(p, d));
            }
            case "gui" -> {
                if (sender instanceof Player p) SigninGui.open(m, p);
            }
            case "top" -> top(sender, args);
            case "admin" -> admin(sender, args);
            default -> m.msg(sender, "usage");
        }
    }

    private void top(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lbr.signin.top")) {
            m.msg(sender, "error.no-permission");
            return;
        }
        boolean byStreak = !(args.length > 1 && args[1].equalsIgnoreCase("total"));
        int page = 1;
        for (int i = 1; i < args.length; i++) {
            try { page = Math.max(1, Integer.parseInt(args[i])); } catch (NumberFormatException ignored) {}
        }
        final int pg = page;
        LocalDate today = m.settings().today();
        m.runAsync(() -> {
            try {
                var list = m.store().top(byStreak, today, (pg - 1) * 10, 10);
                m.runTask(() -> {
                    m.msg(sender, byStreak ? "top.header-streak" : "top.header-total", "page", pg);
                    if (list.isEmpty()) {
                        m.msg(sender, "top.empty");
                        return;
                    }
                    int rank = (pg - 1) * 10;
                    for (var s : list) {
                        rank++;
                        m.msg(sender, "top.entry", "rank", rank, "player", s.name() == null ? s.uuid() : s.name(),
                                "streak", s.streak(), "total", s.total());
                    }
                });
            } catch (Exception e) {
                m.runTask(() -> m.msg(sender, "error.database"));
            }
        });
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lbr.signin.admin")) {
            m.msg(sender, "error.no-permission");
            return;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
            m.msg(sender, "usage-admin");
            return;
        }
        String name = args[2];
        UUID uuid = null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) uuid = online.getUniqueId();
        else {
            try { uuid = m.api().players().uuidOf(name).orElse(null); } catch (Exception ignored) {}
        }
        if (uuid == null) {
            m.msg(sender, "error.player-not-found", "player", name);
            return;
        }
        final UUID target = uuid;
        String actor = sender instanceof Player p ? p.getName() : "console";
        m.runAsync(() -> {
            try {
                m.store().reset(target);
                m.runTask(() -> {
                    m.resetCached(target);
                    m.msg(sender, "admin.reset", "player", name);
                });
                m.audit(actor, "reset", name + " (" + target + ")");
            } catch (Exception e) {
                m.runTask(() -> m.msg(sender, "error.database"));
            }
        });
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("sign", "top"));
            if (sender.hasPermission("lbr.signin.admin")) subs.add("admin");
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) return filter(List.of("streak", "total"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) return filter(List.of("reset"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) return onlinePlayers(args[2]);
        return List.of();
    }
}
