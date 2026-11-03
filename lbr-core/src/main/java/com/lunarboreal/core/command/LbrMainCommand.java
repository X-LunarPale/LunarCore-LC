package com.lunarboreal.core.command;

import com.lunarboreal.api.command.LbrCommand;
import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.core.LunarCorePlugin;
import com.lunarboreal.core.module.LbrModuleManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /lc 主命令：help、version|info、reload、module list|enable|disable|reload|info、web ...、debug。
 */
public final class LbrMainCommand implements TabExecutor {

    public static final String PERM_ADMIN = "lbr.admin";
    public static final String PERM_INFO = "lbr.info";
    public static final String PERM_RELOAD = "lbr.reload";
    public static final String PERM_MODULE = "lbr.module";
    public static final String PERM_WEB = "lbr.web";
    public static final String PERM_DEBUG = "lbr.debug";

    private final LunarCorePlugin plugin;

    public LbrMainCommand(LunarCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player p ? p.getName() : "console";
    }

    private boolean check(CommandSender sender, String perm) {
        if (sender.hasPermission(perm)) return true;
        plugin.messages().send(sender, "core.no-permission", "permission", perm);
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "help", "?" -> plugin.messages().send(sender, "core.help", "label", label);
                case "version", "info", "ver" -> {
                    if (check(sender, PERM_INFO)) info(sender);
                }
                case "reload" -> {
                    if (check(sender, PERM_RELOAD)) reload(sender);
                }
                case "module", "modules", "mod", "m" -> {
                    if (check(sender, PERM_MODULE)) module(sender, label, Arrays.copyOfRange(args, 1, args.length));
                }
                case "web" -> {
                    // 权限由 WebServer 自行检查（/lc web bind 对普通玩家开放）
                    String[] rest = Arrays.copyOfRange(args, 1, args.length);
                    if (!plugin.webServer().handleCommand(sender, rest)) {
                        plugin.messages().send(sender, "core.web-usage", "label", label);
                    }
                }
                case "debug" -> {
                    if (check(sender, PERM_DEBUG)) {
                        boolean on = !plugin.isDebug();
                        plugin.setDebug(on);
                        plugin.messages().send(sender, on ? "core.debug.enabled" : "core.debug.disabled");
                    }
                }
                default -> plugin.messages().send(sender, "core.unknown-subcommand", "label", label);
            }
        } catch (Throwable t) {
            plugin.messages().send(sender, "core.command-error");
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "执行 /" + label + " 出错", t);
        }
        return true;
    }

    private void info(CommandSender sender) {
        LbrModuleManager mm = plugin.moduleManager();
        plugin.messages().send(sender, "core.info",
                "version", plugin.version(),
                "platform", Bukkit.getName() + " " + Bukkit.getVersion(),
                "minecraft", Bukkit.getBukkitVersion(),
                "paper", plugin.isPaper() ? MessageService.rich(plugin.messages().raw("core.word-yes")) : MessageService.rich(plugin.messages().raw("core.word-no")),
                "java", System.getProperty("java.version"),
                "database", plugin.database().describe(),
                "language", plugin.messages().locale(),
                "modules", mm.loaded().size(),
                "discovered", mm.discovered().size(),
                "web", MessageService.rich(plugin.webAddress()),
                "papi", plugin.placeholders().isPapiHooked() ? MessageService.rich(plugin.messages().raw("core.word-yes")) : MessageService.rich(plugin.messages().raw("core.word-no")));
    }

    private void reload(CommandSender sender) {
        long t0 = System.currentTimeMillis();
        plugin.reloadCore();
        int n = plugin.moduleManager().reloadConfigs();
        plugin.audit().log("core", actor(sender), "reload", "重载核心配置与 " + n + " 个模块配置");
        plugin.messages().send(sender, "core.reload.done", "modules", n, "time", System.currentTimeMillis() - t0);
    }

    private void module(CommandSender sender, String label, String[] args) {
        LbrModuleManager mm = plugin.moduleManager();
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            mm.scan();
            plugin.messages().send(sender, "core.module.list-header", "count", mm.loaded().size(), "total", mm.discovered().size());
            for (ModuleDescription d : mm.discovered()) {
                String status = mm.status(d.id());
                plugin.messages().send(sender, "core.module.list-entry",
                        "id", d.id(), "name", d.name(), "version", d.version(),
                        "status", MessageService.rich(plugin.messages().raw("core.module.status." + status)));
            }
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length < 2) {
            plugin.messages().send(sender, "core.module.usage", "label", label);
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "enable" -> {
                if (mm.isEnabled(id)) {
                    plugin.messages().send(sender, "core.module.already-enabled", "id", id);
                    return;
                }
                if (mm.discoveredInfo(id).isEmpty()) mm.scan();
                if (mm.discoveredInfo(id).isEmpty()) {
                    plugin.messages().send(sender, "core.module.not-found", "id", id);
                    return;
                }
                boolean ok = mm.enable(id);
                if (ok && mm.isConfigDisabled(id)) mm.setConfigDisabled(id, false);
                plugin.audit().log("core", actor(sender), "module.enable", id + (ok ? "" : "（失败）"));
                if (ok) plugin.messages().send(sender, "core.module.enabled", "id", id);
                else plugin.messages().send(sender, "core.module.enable-failed", "id", id, "reason", String.valueOf(mm.failure(id)));
            }
            case "disable" -> {
                if (!mm.isEnabled(id)) {
                    plugin.messages().send(sender, "core.module.not-enabled", "id", id);
                    return;
                }
                boolean persist = args.length >= 3 && (args[2].equalsIgnoreCase("--save") || args[2].equalsIgnoreCase("save"));
                mm.disable(id);
                if (persist) mm.setConfigDisabled(id, true);
                plugin.audit().log("core", actor(sender), "module.disable", id + (persist ? "（写入配置）" : ""));
                plugin.messages().send(sender, persist ? "core.module.disabled-saved" : "core.module.disabled", "id", id);
            }
            case "reload" -> {
                boolean ok = mm.reload(id);
                plugin.audit().log("core", actor(sender), "module.reload", id + (ok ? "" : "（失败）"));
                if (ok) plugin.messages().send(sender, "core.module.reloaded", "id", id);
                else plugin.messages().send(sender, "core.module.reload-failed", "id", id, "reason", String.valueOf(mm.failure(id)));
            }
            case "info" -> {
                Optional<LbrModuleManager.Discovered> d = mm.discoveredInfo(id);
                if (d.isEmpty()) {
                    mm.scan();
                    d = mm.discoveredInfo(id);
                }
                if (d.isEmpty()) {
                    plugin.messages().send(sender, "core.module.not-found", "id", id);
                    return;
                }
                ModuleDescription desc = d.get().description();
                String failure = mm.failure(id);
                plugin.messages().send(sender, "core.module.info",
                        "id", desc.id(), "name", desc.name(), "version", desc.version(),
                        "description", desc.description(),
                        "authors", String.join(", ", desc.authors()),
                        "depends", desc.depends().isEmpty() ? "-" : String.join(", ", desc.depends()),
                        "softdepends", desc.softDepends().isEmpty() ? "-" : String.join(", ", desc.softDepends()),
                        "file", d.get().file().getName(),
                        "status", MessageService.rich(plugin.messages().raw("core.module.status." + mm.status(id))),
                        "error", failure == null ? "-" : failure);
            }
            default -> plugin.messages().send(sender, "core.module.usage", "label", label);
        }
    }

    // ------------------------------------------------------------------ Tab

    private static final List<String> SUBS = List.of("help", "info", "version", "reload", "module", "web", "debug");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        try {
            if (args.length <= 1) {
                List<String> allowed = new ArrayList<>();
                for (String s : SUBS) if (allowedSub(sender, s)) allowed.add(s);
                return LbrCommand.filter(allowed, args.length == 0 ? "" : args[0]);
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("web")) {
                List<String> r = plugin.webServer().tabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
                return r == null ? Collections.emptyList() : r;
            }
            if ((sub.equals("module") || sub.equals("modules") || sub.equals("mod") || sub.equals("m")) && sender.hasPermission(PERM_MODULE)) {
                LbrModuleManager mm = plugin.moduleManager();
                if (args.length == 2) return LbrCommand.filter(List.of("list", "enable", "disable", "reload", "info"), args[1]);
                if (args.length == 3) {
                    String action = args[1].toLowerCase(Locale.ROOT);
                    List<String> ids = new ArrayList<>();
                    switch (action) {
                        case "enable" -> {
                            for (ModuleDescription d : mm.discovered()) if (!mm.isEnabled(d.id())) ids.add(d.id());
                        }
                        case "disable", "reload" -> {
                            for (LbrModule m : mm.loaded()) ids.add(m.id());
                        }
                        case "info" -> {
                            for (ModuleDescription d : mm.discovered()) ids.add(d.id());
                        }
                        default -> {
                        }
                    }
                    return LbrCommand.filter(ids, args[2]);
                }
                if (args.length == 4 && args[1].equalsIgnoreCase("disable")) return LbrCommand.filter(List.of("--save"), args[3]);
            }
        } catch (Throwable t) {
            plugin.debug("/lc Tab 补全出错: " + t);
        }
        return Collections.emptyList();
    }

    private boolean allowedSub(CommandSender sender, String sub) {
        return switch (sub) {
            case "info", "version" -> sender.hasPermission(PERM_INFO);
            case "reload" -> sender.hasPermission(PERM_RELOAD);
            case "module" -> sender.hasPermission(PERM_MODULE);
            case "debug" -> sender.hasPermission(PERM_DEBUG);
            default -> true;
        };
    }
}
