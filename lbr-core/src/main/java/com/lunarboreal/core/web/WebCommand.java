package com.lunarboreal.core.web;

import com.lunarboreal.api.LBR;
import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.core.LunarCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** /lc web 子命令（args 不含 "web"），消息 key 为 core.web.*。 */
final class WebCommand {

    static final String PERMISSION = "lbr.web";
    private static final List<String> SUBS = List.of("status", "url", "reload", "user", "resetadmin", "bind");
    private static final List<String> USER_SUBS = List.of("list", "add", "remove", "passwd", "role");

    private final WebServer web;
    private final LunarCorePlugin plugin;

    WebCommand(WebServer web, LunarCorePlugin plugin) {
        this.web = web;
        this.plugin = plugin;
    }

    private void msg(CommandSender to, String key, Object... ph) {
        MessageService m = LBR.get().messages();
        m.send(to, "core.web." + key, ph);
    }

    /** 回到主线程发送消息（玩家可能已离线）。 */
    private void later(CommandSender to, String key, Object... ph) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (to instanceof Player p && !p.isOnline()) return;
            msg(to, key, ph);
        });
    }

    boolean handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("bind")) {
            bind(sender);
            return true;
        }
        if (!sender.hasPermission(PERMISSION)) {
            msg(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "status" -> status(sender);
            case "url" -> msg(sender, "url", "url", web.url());
            case "reload" -> reload(sender);
            case "user", "users" -> user(sender, args);
            case "resetadmin" -> resetAdmin(sender);
            case "help" -> msg(sender, "usage");
            default -> msg(sender, "usage");
        }
        return true;
    }

    private void status(CommandSender sender) {
        msg(sender, "status",
                "state", MessageService.rich(web.isRunning() ? "<green>运行中" : "<red>未运行"),
                "url", web.url(),
                "port", web.port(),
                "users", web.authReady() ? web.auth.users().size() : 0,
                "ws", web.ws.size(),
                "routes", web.routes.size(),
                "panels", web.panelCount());
    }

    private void reload(CommandSender sender) {
        msg(sender, "reload-start");
        web.auditSender(sender, "web.reload", "重载 Web 面板");
        web.runAsync(() -> {
            try {
                // 在主线程重读 config.yml，再异步重启 Web 服务器
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    plugin.reloadConfig();
                    return null;
                }).get();
                web.restart();
                later(sender, web.isRunning() ? "reload-done" : "reload-failed", "url", web.url());
            } catch (Exception e) {
                later(sender, "error", "message", String.valueOf(e.getMessage()));
            }
        });
    }

    private boolean requireAuth(CommandSender sender) {
        if (!web.authReady()) {
            msg(sender, "not-ready");
            return false;
        }
        return true;
    }

    private void user(CommandSender sender, String[] args) {
        if (!requireAuth(sender)) return;
        String op = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "list" -> {
                msg(sender, "user-list-header", "count", web.auth.users().size());
                for (AuthManager.UserRow u : web.auth.users()) {
                    msg(sender, "user-list-entry", "user", u.username(), "role", u.role(),
                            "mc", u.mcName() == null ? "-" : u.mcName());
                }
            }
            case "add" -> {
                if (args.length < 4) {
                    msg(sender, "usage");
                    return;
                }
                String name = args[2], pw = args[3], role = args.length > 4 ? args[4] : "viewer";
                async(sender, () -> {
                    web.auth.createUser(name, pw, role);
                    web.auditSender(sender, "user.add", name + " 角色 " + role);
                    later(sender, "user-added", "user", name, "role", role);
                });
            }
            case "remove", "del", "delete" -> {
                if (args.length < 3) {
                    msg(sender, "usage");
                    return;
                }
                String name = args[2];
                async(sender, () -> {
                    AuthManager.UserRow u = web.auth.user(name);
                    if (u != null && u.role().equalsIgnoreCase("admin") && web.auth.countAdmins() <= 1) {
                        throw new IllegalArgumentException("至少需要保留一个管理员");
                    }
                    web.auth.deleteUser(name);
                    web.refreshWsUsers();
                    web.auditSender(sender, "user.remove", name);
                    later(sender, "user-removed", "user", name);
                });
            }
            case "passwd", "password" -> {
                if (args.length < 4) {
                    msg(sender, "usage");
                    return;
                }
                String name = args[2], pw = args[3];
                async(sender, () -> {
                    web.auth.setPassword(name, pw);
                    web.refreshWsUsers();
                    web.auditSender(sender, "user.passwd", name);
                    later(sender, "user-passwd", "user", name);
                });
            }
            case "role" -> {
                if (args.length < 4) {
                    msg(sender, "usage");
                    return;
                }
                String name = args[2], role = args[3];
                async(sender, () -> {
                    web.auth.setRole(name, role);
                    web.refreshWsUsers();
                    web.auditSender(sender, "user.role", name + " → " + role);
                    later(sender, "user-role", "user", name, "role", role);
                });
            }
            default -> msg(sender, "usage");
        }
    }

    private void resetAdmin(CommandSender sender) {
        if (!requireAuth(sender)) return;
        if (!(sender instanceof ConsoleCommandSender) && !sender.isOp()) {
            msg(sender, "console-only");
            return;
        }
        async(sender, () -> {
            String pw = web.auth.resetAdmin();
            web.refreshWsUsers();
            web.printAdminBanner("admin", pw, false);
            web.auditSender(sender, "user.resetadmin", "重置 admin 密码");
            if (!(sender instanceof ConsoleCommandSender)) later(sender, "resetadmin", "user", "admin", "password", pw);
            else later(sender, "resetadmin-console");
        });
    }

    private void bind(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            msg(sender, "player-only");
            return;
        }
        if (!requireAuth(sender)) return;
        String code = web.auth.createBindCode(p.getUniqueId(), p.getName());
        msg(sender, "bind-code", "code", code, "minutes", 5, "url", web.url());
    }

    private interface Task {
        void run() throws Exception;
    }

    private void async(CommandSender sender, Task task) {
        web.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                later(sender, "error", "message", String.valueOf(e.getMessage()));
            }
        });
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String pre = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> subs = sender.hasPermission(PERMISSION) ? SUBS : List.of("bind");
            return subs.stream().filter(s -> s.startsWith(pre)).collect(Collectors.toList());
        }
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args[0].equalsIgnoreCase("user")) {
            if (args.length == 2) {
                return USER_SUBS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
            }
            String op = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && !op.equals("add") && web.authReady()) {
                return web.auth.users().stream().map(AuthManager.UserRow::username)
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
            }
            if (((op.equals("add") && args.length == 5) || (op.equals("role") && args.length == 4)) && web.authReady()) {
                List<String> out = new ArrayList<>();
                for (AuthManager.RoleRow r : web.auth.roles()) out.add(r.name());
                return out;
            }
        }
        return List.of();
    }
}
