package com.lunarboreal.journal;

import com.lunarboreal.api.command.LcCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.logging.Level;

final class JournalCommand extends LcCommand {

    private final JournalModule module;

    JournalCommand(JournalModule module) {
        super("journal", "lc.journal.use");
        this.module = module;
        description("Personal exploration journal").usage("/journal [stats]").playerOnly(true);
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        Player player = (Player) sender;
        if (!module.ready()) {
            module.msg(player, "error.loading");
            return;
        }
        if (args.length == 0) {
            JournalGui.open(module, player);
        } else if (args.length == 1 && "stats".equalsIgnoreCase(args[0])) {
            var uuid = player.getUniqueId();
            if (!module.executeIO(() -> {
                try {
                    var stats = module.repository().stats(uuid);
                    module.onMain(() -> {
                        if (player.isOnline()) module.msg(player, "stats", "biomes", stats.biomes(),
                                "advancements", stats.advancements(), "fishing", stats.fishing(), "total", stats.total());
                    });
                } catch (Exception error) {
                    module.logger().log(Level.WARNING, "Cannot load journal stats", error);
                    module.onMain(() -> { if (player.isOnline()) module.msg(player, "error.database"); });
                }
            })) module.msg(player, "error.loading");
        } else {
            module.msg(player, "usage");
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return args.length == 1 ? filter(List.of("stats"), args[0]) : List.of();
    }
}
