package com.lunarboreal.economy.command;

import com.lunarboreal.api.command.LbrCommand;
import com.lunarboreal.api.service.EconomyService.Result;
import com.lunarboreal.economy.EconomyModule;
import com.lunarboreal.economy.EconomyServiceImpl;
import com.lunarboreal.economy.EconomySettings;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /pay <玩家> <金额>、/pay confirm */
public final class PayCommand extends LbrCommand {

    private record Pending(UUID target, BigDecimal amount, long expires) {}

    private final EconomyModule m;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public PayCommand(EconomyModule m) {
        super("pay", "lbr.economy.pay");
        this.m = m;
        description("向其他玩家转账");
        usage("/pay <玩家> <金额>");
        playerOnly(true);
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player p)) return;
        EconomyServiceImpl eco = m.service();
        EconomySettings st = m.settings();
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            Pending pd = pending.remove(p.getUniqueId());
            if (pd == null) {
                m.msg(p, "pay.no-pending");
                return;
            }
            if (System.currentTimeMillis() > pd.expires()) {
                m.msg(p, "pay.expired");
                return;
            }
            doPay(p, pd.target(), pd.amount());
            return;
        }
        if (args.length < 2) {
            m.msg(p, "usage.pay");
            return;
        }
        UUID target = eco.resolve(args[0]);
        if (target == null || !eco.hasAccount(target)) {
            m.msg(p, "error.player-not-found", "player", args[0]);
            return;
        }
        if (target.equals(p.getUniqueId())) {
            m.msg(p, "pay.self");
            return;
        }
        BigDecimal amount = st.parseAmount(args[1]);
        if (amount == null || amount.signum() <= 0) {
            m.msg(p, "error.invalid-amount", "input", args[1]);
            return;
        }
        if (amount.compareTo(st.payMin) < 0) {
            m.msg(p, "pay.min", "min", eco.format(st.payMin));
            return;
        }
        BigDecimal fee = st.fee(amount);
        if (st.confirmThreshold != null && amount.compareTo(st.confirmThreshold) >= 0) {
            pending.put(p.getUniqueId(), new Pending(target, amount, System.currentTimeMillis() + st.confirmTimeout * 1000L));
            m.msg(p, "pay.confirm", "player", eco.nameOf(target), "amount", eco.format(amount),
                    "fee", eco.format(fee), "total", eco.format(amount.add(fee)), "seconds", st.confirmTimeout);
            return;
        }
        doPay(p, target, amount);
    }

    private void doPay(Player p, UUID target, BigDecimal amount) {
        EconomyServiceImpl eco = m.service();
        BigDecimal fee = m.settings().fee(amount);
        Result r = eco.transfer(p.getUniqueId(), target, amount, fee, "转账", p.getName());
        String name = eco.nameOf(target);
        if (!r.success()) {
            m.msg(p, "error." + r.error(), "need", eco.format(amount.add(fee)), "balance", eco.format(r.balance()));
            return;
        }
        if (fee.signum() > 0) {
            m.msg(p, "pay.sent-fee", "player", name, "amount", eco.format(amount), "fee", eco.format(fee), "balance", eco.format(r.balance()));
        } else {
            m.msg(p, "pay.sent", "player", name, "amount", eco.format(amount), "balance", eco.format(r.balance()));
        }
        Player t = Bukkit.getPlayer(target);
        if (t != null) m.msg(t, "pay.received", "player", p.getName(), "amount", eco.format(amount));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new java.util.ArrayList<>(onlinePlayers(args[0]));
            out.addAll(filter(List.of("confirm"), args[0]));
            return out;
        }
        return List.of();
    }
}
