package com.lunarboreal.economy.command;

import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.economy.EconomyModule;
import com.lunarboreal.economy.EconomyServiceImpl;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** /money [玩家]、/money log [玩家] [页] */
public final class MoneyCommand extends LcCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final EconomyModule m;

    public MoneyCommand(EconomyModule m) {
        super("money", "lc.economy.balance", "bal", "balance");
        this.m = m;
        description("查看余额 / 流水");
        usage("/money [玩家] | /money log [玩家] [页]");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("log")) {
            log(sender, args);
            return;
        }
        EconomyServiceImpl eco = m.service();
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                m.msg(sender, "usage.money");
                return;
            }
            m.msg(sender, "balance.self", "amount", eco.format(eco.balance(p.getUniqueId())));
            return;
        }
        if (!sender.hasPermission("lc.economy.balance.others")) {
            m.msg(sender, "error.no-permission");
            return;
        }
        UUID target = eco.resolve(args[0]);
        if (target == null || !eco.hasAccount(target)) {
            m.msg(sender, "error.player-not-found", "player", args[0]);
            return;
        }
        m.msg(sender, "balance.other", "player", eco.nameOf(target), "amount", eco.format(eco.balance(target)));
    }

    private void log(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lc.economy.log")) {
            m.msg(sender, "error.no-permission");
            return;
        }
        EconomyServiceImpl eco = m.service();
        UUID target;
        int page = 1;
        int idx = 1;
        if (args.length > idx && !isInt(args[idx])) {
            if (!sender.hasPermission("lc.economy.log.others")
                    && !(sender instanceof Player p && p.getName().equalsIgnoreCase(args[idx]))) {
                m.msg(sender, "error.no-permission");
                return;
            }
            target = eco.resolve(args[idx]);
            if (target == null) {
                m.msg(sender, "error.player-not-found", "player", args[idx]);
                return;
            }
            idx++;
        } else if (sender instanceof Player p) {
            target = p.getUniqueId();
        } else {
            m.msg(sender, "usage.money");
            return;
        }
        if (args.length > idx && isInt(args[idx])) page = Math.max(1, Integer.parseInt(args[idx]));
        final int pg = page;
        final UUID tgt = target;
        int size = m.settings().logPageSize;
        String name = eco.nameOf(target);
        m.runAsync(() -> {
            try {
                List<EconomyServiceImpl.Tx> list = eco.queryTx(tgt, null, (pg - 1) * size, size);
                long total = eco.countTx(tgt, null);
                m.runTask(() -> {
                    long pages = Math.max(1, (total + size - 1) / size);
                    m.msg(sender, "log.header", "player", name, "page", pg, "pages", pages);
                    if (list.isEmpty()) {
                        m.msg(sender, "log.empty");
                        return;
                    }
                    for (EconomyServiceImpl.Tx t : list) {
                        BigDecimal c = t.change();
                        m.msg(sender, c.signum() >= 0 ? "log.entry-in" : "log.entry-out",
                                "time", TIME.format(Instant.ofEpochMilli(t.time())),
                                "change", m.settings().number(c.abs()), "balance", m.settings().number(t.balance()),
                                "reason", t.reason() == null ? "-" : t.reason(),
                                "operator", t.operator() == null ? "-" : t.operator());
                    }
                });
            } catch (Exception e) {
                m.runTask(() -> m.msg(sender, "error.database"));
            }
        });
    }

    private static boolean isInt(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>(filter(List.of("log"), args[0]));
            if (sender.hasPermission("lc.economy.balance.others")) out.addAll(onlinePlayers(args[0]));
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("log") && sender.hasPermission("lc.economy.log.others")) {
            return onlinePlayers(args[1]);
        }
        return List.of();
    }
}
