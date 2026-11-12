package com.lunarboreal.anticheat;

import com.lunarboreal.api.command.LcCommand;
import org.bukkit.command.CommandSender;
import java.util.List;

/** Bridge configuration only; Grim commands retain all enforcement administration. */
public final class AcCommand extends LcCommand {
    private final AntiCheatModule module;
    public AcCommand(AntiCheatModule module) {
        super("ac", "lc.anticheat.command", "anticheat");
        this.module = module;
        description("GrimAC 桥接管理");
        usage("/ac [status|reload]");
    }
    @Override public void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            module.msg(sender, "command.status", "status", module.bridge().status(),
                    "version", module.bridge().version(), "count", module.recent().size());
        } else if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("lc.anticheat.reload")) { module.msg(sender, "command.no-permission"); return; }
            module.onReload();
            module.audit(sender.getName(), "bridge-reload", "重载桥接配置");
            module.msg(sender, "command.reloaded");
        } else module.msg(sender, "command.usage");
    }
    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return args.length == 1 ? filter(List.of("status", "reload"), args[0]) : List.of();
    }
}
