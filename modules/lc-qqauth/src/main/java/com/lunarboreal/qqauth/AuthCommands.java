package com.lunarboreal.qqauth;

import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.qqauth.auth.AuthService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import java.util.*;

final class AuthCommands {
    static final Set<String> SECRET_COMMANDS = Set.of("login", "l", "register", "reg", "changepassword", "changepass", "unregister", "qqauthreset");
    static void register(QQAuthModule m) {
        command(m,"login", "l"); command(m,"register","reg"); command(m,"changepassword","changepass");
        command(m,"unregister"); command(m,"logout"); command(m,"unbind"); command(m,"qqauthreset");
        command(m,"qqauth");
    }
    private static void command(QQAuthModule m, String name, String... aliases) {
        m.registerCommand(new LcCommand(name, null, aliases) {
            @Override public void execute(CommandSender sender, String label, String[] args) { AuthCommands.execute(m,sender,name,args); }
            @Override public List<String> tabComplete(CommandSender s,String alias,String[] args) { return List.of(); }
        });
    }
    static void execute(QQAuthModule m, CommandSender sender, String label, String[] args) {
        if (label.equals("qqauth")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("bind")) bind(m, sender);
            else admin(m,sender,args);
            return;
        }
        if (!m.ready) { sender.sendMessage(m.componentMsg("command.not-ready")); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage(m.componentMsg("command.player-only")); return; }
        UUID id=p.getUniqueId(); String name=p.getName(),ip=m.ip(p);
        boolean logged=m.authenticated(p);
        if (Set.of("changepassword","changepass","unregister","logout","unbind").contains(label) && !logged) {
            p.sendMessage(m.componentMsg("command.login-required")); return;
        }
        if (Set.of("changepassword", "changepass", "qqauthreset", "unregister", "unbind").contains(label) && !m.bound.contains(id)) {
            p.sendMessage(m.componentMsg("command.binding-required")); return;
        }
        if (label.equals("logout")) { m.auth.logout(id); m.granted.remove(id, p); m.movement.update(p, true); m.joined.put(id,System.currentTimeMillis()); p.sendMessage(m.componentMsg("command.logged-out")); return; }
        String usage = m.msg(switch(label) {
            case "register","reg" -> "usage.register";
            case "login","l" -> "usage.login";
            case "changepassword","changepass" -> "usage.change-password";
            case "unregister" -> "usage.unregister";
            case "qqauthreset" -> "usage.reset-password";
            default -> "usage.unbind";
        });
        int expected = switch(label) { case "register","reg","changepassword","changepass","qqauthreset" -> 2; case "unbind" -> 0; default -> 1; };
        if (args.length!=expected || (label.equals("qqauthreset") && !args[0].equalsIgnoreCase("password"))) { p.sendMessage(Component.text(usage)); return; }
        if ((label.equals("register")||label.equals("reg")) && !args[0].equals(args[1])) { p.sendMessage(m.componentMsg("command.password-mismatch")); return; }
        if (logged && Set.of("register","reg","login","l").contains(label)) { p.sendMessage(m.componentMsg("command.already-logged-in")); return; }
        if (!m.busy.add(id)) { p.sendMessage(m.componentMsg("command.busy")); return; }
        boolean accepted=m.submit(() -> {
            try {
                String reply;
                AuthService.Result result=null;
                switch(label) {
                    case "register","reg" -> result=m.auth.register(id,name,ip,args[0]);
                    case "login","l" -> result=m.auth.login(id,name,ip,args[0]);
                    case "changepassword","changepass" -> {
                        result=m.auth.changePassword(id,name,ip,args[0],args[1]);
                        if(result==AuthService.Result.SUCCESS) m.bindings.cancel(id);
                    }
                    case "unregister" -> {
                        result=m.auth.unregister(id,name,ip,args[0]);
                        if(result==AuthService.Result.SUCCESS) m.bindings.cancel(id);
                    }
                    case "unbind" -> {
                        var code=m.bindings.issueUnbind(id);
                        reply=m.msg("command.unbind-code","code",code.code());
                        respond(m,id,reply); return;
                    }
                    case "qqauthreset" -> {
                        var account=m.auth.account(name).orElseThrow(() -> new IllegalStateException(m.msg("command.account-not-registered")));
                        if(!account.uuid().equals(id)) throw new IllegalStateException(m.msg("command.account-identity-mismatch"));
                        var code=m.bindings.issueReset(id,m.auth.preparePassword(args[1]),account.generation());
                        respond(m,id,m.msg("command.reset-code","code",code.code())); return;
                    }
                }
                reply=result==AuthService.Result.SUCCESS ? m.msg("command.success") : m.msg("command.failed","status",m.authStatus(result));
                if(result==AuthService.Result.SUCCESS) {
                    if (Set.of("login","l","register","reg").contains(label)) {
                        m.sync(() -> {
                            if (Bukkit.getPlayer(id) == p && p.isOnline() && m.bindingAllowed(id)) {
                                 m.granted.put(id, p);
                                 m.movement.update(p, !m.authenticated(p));
                                 m.remindBinding(p);
                             }
                            else if (!m.connections.containsKey(id)) m.auth.logout(id);
                        });
                    }
                    m.audit(name,label,"账号认证操作（不记录密码）");
                    if(Set.of("changepassword","changepass","unregister").contains(label)) m.kick(id,m.msg("kick.account-updated"));
                }
                respond(m,id,reply);
            } catch(Exception ex) {
                m.logger().warning("账号操作未完成："+ex.getClass().getSimpleName());
                respond(m,id,m.msg("command.operation-error"));
            } finally { Arrays.fill(args, ""); m.sync(() -> m.busy.remove(id)); }
        });
        if(!accepted) { m.busy.remove(id); p.sendMessage(m.componentMsg("command.queue-full")); }
    }
    private static void respond(QQAuthModule m,UUID id,String text) {
        m.sync(() -> { Player p=Bukkit.getPlayer(id); if(p!=null) p.sendMessage(Messages.render(text)); });
    }
    private static void bind(QQAuthModule m, CommandSender sender) {
        if (!m.ready) { sender.sendMessage(m.componentMsg("command.not-ready")); return; }
        if (!(sender instanceof Player player)) { sender.sendMessage(m.componentMsg("command.player-only")); return; }
        if (!m.authenticated(player)) { player.sendMessage(m.componentMsg("command.login-required")); return; }
        UUID uuid = player.getUniqueId();
        if (m.bound.contains(uuid)) { player.sendMessage(m.componentMsg("command.already-bound")); return; }
        if (!m.busy.add(uuid)) { player.sendMessage(m.componentMsg("command.busy")); return; }
        String name = player.getName();
        if (!m.submit(() -> {
            try {
                var code = m.bindings.issueBind(uuid, name);
                m.sync(() -> {
                    if (m.connections.get(uuid) == player && player.isOnline() && m.authenticated(player) && !m.bound.contains(uuid))
                        player.sendMessage(m.componentMsg("command.bind-code", "player", name, "code", code.code(),
                                "group", m.config().getString("integration.group-id", ""),
                                "ttl", m.bindingSettings().bindTtl().toSeconds()));
                });
            } catch (Exception ex) {
                respond(m, uuid, m.msg("command.operation-error"));
            } finally { m.busy.remove(uuid); }
        })) { m.busy.remove(uuid); player.sendMessage(m.componentMsg("command.queue-full")); }
    }
    private static void admin(QQAuthModule m,CommandSender sender,String[] args) {
        if(args.length==0) { sender.sendMessage(m.componentMsg("usage.admin-help")); return; }
        if(sender instanceof Player p && !m.authenticated(p)) { sender.sendMessage(m.componentMsg("command.login-required")); return; }
        if(!sender.hasPermission("qqauth.admin")) { sender.sendMessage(m.componentMsg("command.no-permission")); return; }
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("reload")) {
            boolean ok=m.api().modules().reload("qqauth");
            sender.sendMessage(m.componentMsg(ok ? "command.admin-reloaded" : "command.admin-reload-failed"));
            return;
        }
        if (!m.ready) { sender.sendMessage(m.componentMsg("command.not-ready")); return; }
        if(sub.equals("status")) { sender.sendMessage(m.componentMsg("command.admin-status","ready",m.ready,"count",m.auth.accounts().size())); return; }
        if(sub.equals("reset")) { sender.sendMessage(m.componentMsg("command.admin-reset")); return; }
        if(args.length!=2 || !Set.of("forceunbind","lock","unlock","delete","importbindings").contains(sub)) { sender.sendMessage(m.componentMsg("usage.admin-actions")); return; }
        String actor=sender.getName(),target=args[1];
        if(sub.equals("importbindings")) {
            if(sender instanceof Player || !target.equals("confirm")) { sender.sendMessage(m.componentMsg("command.import-console-only")); return; }
        }
        if(!m.submit(() -> {
            try {
                if(sub.equals("importbindings")) { BindingImport.importFile(m); }
                else if(sub.equals("forceunbind")) {
                    var result=m.bindings.forceUnbind(target);
                    if(result.binding()!=null) { m.bound.remove(result.binding().uuid()); m.kick(result.binding().uuid(),m.msg("kick.admin-unbound")); }
                    else {
                        String reply=m.msg("command.failed","status",m.bindingStatus(result.status()));
                        m.sync(() -> sender.sendMessage(Component.text(reply))); return;
                    }
                } else {
                    var account=m.auth.account(target).orElseThrow();
                    AuthService.Result result=sub.equals("delete")?m.auth.deleteAccount(target):m.auth.setLocked(target,sub.equals("lock"));
                    if(result!=AuthService.Result.SUCCESS) {
                        String reply=m.msg("command.failed","status",m.authStatus(result));
                        m.sync(() -> sender.sendMessage(Component.text(reply))); return;
                    }
                    m.bindings.cancel(account.uuid());
                    m.kick(account.uuid(),m.msg("kick.admin-updated"));
                }
                m.audit(actor,sub,target);
                m.sync(() -> sender.sendMessage(m.componentMsg("command.admin-success")));
            } catch(Exception ex) { m.sync(() -> sender.sendMessage(m.componentMsg("command.admin-error","error",ex.getClass().getSimpleName()))); }
        })) sender.sendMessage(m.componentMsg("command.admin-queue-full"));
    }
}
