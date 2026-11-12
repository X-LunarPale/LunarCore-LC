package com.lunarboreal.economy.command;

import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.api.service.EconomyService.TopEntry;
import com.lunarboreal.economy.EconomyModule;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /baltop [页] */
public final class BaltopCommand extends LcCommand {

    private final EconomyModule m;

    public BaltopCommand(EconomyModule m) {
        super("baltop", "lc.economy.baltop", "moneytop", "balancetop");
        this.m = m;
        description("财富排行榜");
        usage("/baltop [页]");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        int page = 1;
        if (args.length > 0) {
            try { page = Math.max(1, Integer.parseInt(args[0])); } catch (NumberFormatException ignored) {}
        }
        List<TopEntry> top = m.service().topAll();
        int size = m.settings().baltopPageSize;
        int pages = Math.max(1, (top.size() + size - 1) / size);
        page = Math.min(page, pages);
        m.msg(sender, "baltop.header", "page", page, "pages", pages,
                "total", m.service().format(m.service().totalMoney()));
        if (top.isEmpty()) {
            m.msg(sender, "baltop.empty");
            return;
        }
        int from = (page - 1) * size;
        for (int i = from; i < Math.min(top.size(), from + size); i++) {
            TopEntry e = top.get(i);
            m.msg(sender, "baltop.entry", "rank", i + 1, "player", e.name(), "amount", m.service().format(e.balance()));
        }
        if (sender instanceof Player p) {
            int r = m.service().rank(p.getUniqueId());
            if (r > 0) m.msg(sender, "baltop.self", "rank", r);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return List.of();
    }
}
