package com.lunarboreal.economy.command;

import com.lunarboreal.api.command.LbrCommand;
import com.lunarboreal.api.service.EconomyService.Result;
import com.lunarboreal.economy.EconomyModule;
import com.lunarboreal.economy.EconomyServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /eco give|take|set|reset <玩家|*> [金额] */
public final class EcoCommand extends LbrCommand {

    private static final List<String> SUBS = List.of("give", "take", "set", "reset");

    private final EconomyModule m;

    public EcoCommand(EconomyModule m) {
        super("eco", "lbr.economy.admin", "economy");
        this.m = m;
        description("经济管理");
        usage("/eco <give|take|set|reset> <玩家|*> [金额]");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2 || !SUBS.contains(args[0].toLowerCase(Locale.ROOT))) {
            m.msg(sender, "usage.eco");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        EconomyServiceImpl eco = m.service();
        BigDecimal amount;
        if (sub.equals("reset")) {
            amount = m.settings().initialBalance;
        } else {
            if (args.length < 3) {
                m.msg(sender, "usage.eco");
                return;
            }
            amount = m.settings().parseAmount(args[2]);
            if (amount == null || amount.signum() < 0) {
                m.msg(sender, "error.invalid-amount", "input", args[2]);
                return;
            }
        }
        List<UUID> targets = new ArrayList<>();
        if (args[1].equals("*")) {
            for (Player p : Bukkit.getOnlinePlayers()) targets.add(p.getUniqueId());
        } else {
            UUID u = eco.resolve(args[1]);
            if (u == null) {
                m.msg(sender, "error.player-not-found", "player", args[1]);
                return;
            }
            targets.add(u);
        }
        String actor = sender instanceof Player p ? p.getName() : "console";
        String reason = "管理员 " + sub;
        int ok = 0;
        for (UUID u : targets) {
            Result r = switch (sub) {
                case "give" -> eco.deposit(u, amount, reason, actor);
                case "take" -> eco.withdraw(u, amount, reason, actor);
                default -> eco.set(u, amount, reason, actor);
            };
            String name = eco.nameOf(u);
            if (r.success()) {
                ok++;
                if (targets.size() == 1) {
                    m.msg(sender, "eco." + sub, "player", name, "amount", eco.format(amount), "balance", eco.format(r.balance()));
                }
                Player online = Bukkit.getPlayer(u);
                if (online != null && online != sender) {
                    m.msg(online, "eco.notify", "amount", eco.format(r.balance()));
                }
            } else if (targets.size() == 1) {
                m.msg(sender, "error." + r.error(), "need", eco.format(amount), "balance", eco.format(r.balance()));
            }
        }
        if (targets.size() != 1) m.msg(sender, "eco.batch", "count", ok, "total", targets.size());
        m.audit(actor, "eco." + sub, args[1] + " " + amount.toPlainString() + "（成功 " + ok + "/" + targets.size() + "）");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
        if (args.length == 2) {
            List<String> out = new ArrayList<>(onlinePlayers(args[1]));
            if ("*".startsWith(args[1])) out.add("*");
            return out;
        }
        return List.of();
    }
}
