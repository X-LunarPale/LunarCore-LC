package com.lunarboreal.chat;

import com.lunarboreal.api.command.LcCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 聊天模块全部命令。 */
final class ChatCommands {

    @FunctionalInterface
    interface Exec { void run(CommandSender sender, String label, String[] args); }

    @FunctionalInterface
    interface Tab { List<String> complete(CommandSender sender, String[] args); }

    private static final class Cmd extends LcCommand {
        private final Exec exec;
        private Tab tab;

        Cmd(String name, String perm, Exec exec, String... aliases) {
            super(name, perm, aliases);
            this.exec = exec;
        }

        Cmd tab(Tab t) { this.tab = t; return this; }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            exec.run(sender, label, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (tab != null) return tab.complete(sender, args);
            return super.tabComplete(sender, alias, args);
        }
    }

    private ChatCommands() {}

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private static void usage(ChatModule m, CommandSender s, String usage) {
        m.msg(s, "usage", "usage", usage);
    }

    static void registerAll(ChatModule m) {
        // ---------- 私聊 ----------
        m.registerCommand(new Cmd("msg", "lc.chat.msg", (s, l, a) -> {
            if (a.length < 2) { usage(m, s, "/" + l + " <玩家> <消息>"); return; }
            Player target = m.findVisible(s, a[0]);
            if (target == null) { m.msg(s, "player-not-found", "player", a[0]); return; }
            sendPrivate(m, s, target, join(a, 1));
        }, "tell", "w", "m", "whisper", "pm").override(true).description("私聊").usage("/msg <玩家> <消息>"));

        m.registerCommand(new Cmd("reply", "lc.chat.reply", (s, l, a) -> {
            if (!(s instanceof Player p)) { m.msg(s, "only-player"); return; }
            if (a.length < 1) { usage(m, s, "/" + l + " <消息>"); return; }
            UUID last = m.user(p).lastMessaged;
            Player target = last == null ? null : Bukkit.getPlayer(last);
            if (target == null) { m.msg(s, "msg.no-reply"); return; }
            sendPrivate(m, s, target, join(a, 0));
        }, "r").description("回复私聊"));

        m.registerCommand(new Cmd("msgtoggle", "lc.chat.msgtoggle", (s, l, a) -> {
            if (!(s instanceof Player p)) { m.msg(s, "only-player"); return; }
            ChatUser u = m.user(p);
            u.msgDisabled = !u.msgDisabled;
            saveUser(m, p, u);
            m.msg(p, u.msgDisabled ? "msg.toggle-off" : "msg.toggle-on");
        }, "togglemsg").playerOnly(true));

        m.registerCommand(new Cmd("socialspy", "lc.chat.socialspy", (s, l, a) -> {
            if (!(s instanceof Player p)) { m.msg(s, "only-player"); return; }
            ChatUser u = m.user(p);
            u.socialSpy = !u.socialSpy;
            saveUser(m, p, u);
            m.msg(p, u.socialSpy ? "socialspy.on" : "socialspy.off");
        }, "spy").playerOnly(true));

        m.registerCommand(new Cmd("ignore", "lc.chat.ignore", (s, l, a) -> {
            if (!(s instanceof Player p)) { m.msg(s, "only-player"); return; }
            ChatUser u = m.user(p);
            if (a.length == 0) {
                if (u.ignored.isEmpty()) { m.msg(p, "ignore.none"); return; }
                List<String> names = new ArrayList<>();
                for (UUID id : u.ignored) names.add(m.api().players().nameOf(id));
                m.msg(p, "ignore.list", "list", String.join(", ", names));
                return;
            }
            Optional<UUID> id = resolveUuid(m, a[0]);
            if (id.isEmpty()) { m.msg(p, "player-not-found", "player", a[0]); return; }
            if (id.get().equals(p.getUniqueId())) { m.msg(p, "ignore.self"); return; }
            Player tp = Bukkit.getPlayer(id.get());
            if (tp != null && tp.hasPermission("lc.chat.ignore.exempt")) { m.msg(p, "ignore.exempt"); return; }
            String name = m.api().players().nameOf(id.get());
            if (u.ignored.remove(id.get())) {
                m.msg(p, "ignore.removed", "player", name);
            } else {
                u.ignored.add(id.get());
                m.msg(p, "ignore.added", "player", name);
            }
            saveUser(m, p, u);
        }).playerOnly(true).description("屏蔽/取消屏蔽玩家"));

        m.registerCommand(new Cmd("unignore", "lc.chat.ignore", (s, l, a) -> {
            if (!(s instanceof Player p)) { m.msg(s, "only-player"); return; }
            if (a.length < 1) { usage(m, s, "/unignore <玩家>"); return; }
            Optional<UUID> id = resolveUuid(m, a[0]);
            ChatUser u = m.user(p);
            if (id.isPresent() && u.ignored.remove(id.get())) {
                saveUser(m, p, u);
                m.msg(p, "ignore.removed", "player", m.api().players().nameOf(id.get()));
            } else {
                m.msg(p, "player-not-found", "player", a[0]);
            }
        }).playerOnly(true));

        // ---------- 邮件 ----------
        m.registerCommand(new Cmd("mail", "lc.chat.mail", (s, l, a) -> mail(m, s, a)).tab((s, a) ->
                a.length == 1 ? LcCommand.filter(List.of("read", "send", "sendall", "clear"), a[0])
                        : a.length == 2 && a[0].equalsIgnoreCase("send") ? LcCommand.onlinePlayers(a[1]) : List.of()));

        // ---------- 昵称 ----------
        m.registerCommand(new Cmd("nick", "lc.chat.nick", (s, l, a) -> nick(m, s, a), "nickname").description("设置昵称"));
        m.registerCommand(new Cmd("realname", "lc.chat.realname", (s, l, a) -> {
            if (a.length < 1) { usage(m, s, "/realname <昵称>"); return; }
            String q = a[0];
            for (Player p : Bukkit.getOnlinePlayers()) {
                String plain = ChatFormatter.plain(p.displayName());
                if (plain.equalsIgnoreCase(q) || plain.toLowerCase().contains(q.toLowerCase())) {
                    m.msg(s, "nick.realname", "nick", plain, "player", p.getName());
                    return;
                }
            }
            m.msg(s, "nick.realname-none");
        }));

        // ---------- 广播与信息 ----------
        m.registerCommand(new Cmd("broadcast", "lc.chat.broadcast", (s, l, a) -> {
            if (a.length < 1) { usage(m, s, "/" + l + " <消息>"); return; }
            String text = join(a, 0);
            m.broadcast(text);
            m.audit(s.getName(), "broadcast", text);
        }, "bc", "bcast", "announce").description("全服广播"));

        m.registerCommand(new Cmd("motd", "lc.chat.motd", (s, l, a) -> m.sendMotd(s)));
        m.registerCommand(new Cmd("rules", "lc.chat.rules", (s, l, a) -> {
            for (String line : m.config().getStringList("rules")) {
                s.sendMessage(ChatFormatter.render(s instanceof Player p ? p : null, line, m.vars("player", s.getName())));
            }
        }));

        m.registerCommand(new Cmd("helpop", "lc.chat.helpop", (s, l, a) -> {
            if (a.length < 1) { usage(m, s, "/helpop <消息>"); return; }
            String text = join(a, 0);
            Component c = ChatFormatter.render(null, m.config().getString("helpop.format", "{player}: <message>"),
                    m.vars("player", s.getName()), Placeholder.unparsed("message", text));
            int n = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("lc.chat.helpop.receive")) {
                    p.sendMessage(c);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                    n++;
                }
            }
            Bukkit.getConsoleSender().sendMessage(c);
            m.msg(s, "helpop.sent", "count", n);
            m.logChat(s instanceof Player p ? p : null, "helpop", text);
        }, "ac").description("向在线管理员求助"));

        m.registerCommand(new Cmd("me", "lc.chat.me", (s, l, a) -> {
            if (a.length < 1) { usage(m, s, "/me <动作>"); return; }
            if (s instanceof Player p && m.isMuted(p)) { m.msg(s, "filter.muted"); return; }
            String text = join(a, 0);
            Component c = m.api().messages().component("chat.me", "player", s.getName(),
                    "message", text);
            Bukkit.getServer().sendMessage(c);
            m.logChat(s instanceof Player p ? p : null, "me", text);
        }, "action", "emote").override(true));

        m.registerCommand(new Cmd("clearchat", "lc.chat.clearchat", (s, l, a) -> {
            Component blank = Component.text(" ");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("lc.chat.clearchat.exempt")) continue;
                for (int i = 0; i < 100; i++) p.sendMessage(blank);
            }
            Bukkit.getOnlinePlayers().forEach(p -> m.msg(p, "clearchat.done", "actor", s.getName()));
            m.audit(s.getName(), "clearchat", "");
        }, "cc").description("清空聊天"));

        m.registerCommand(new Cmd("staffchat", "lc.chat.staffchat", (s, l, a) -> {
            if (a.length > 0) { staffChat(m, s, join(a, 0)); return; }
            if (!(s instanceof Player p)) { usage(m, s, "/sc <消息>"); return; }
            ChatUser u = m.user(p);
            u.staffChat = !u.staffChat;
            m.msg(p, u.staffChat ? "staffchat.on" : "staffchat.off");
        }, "sc", "adminchat").description("管理频道"));

        m.registerCommand(new Cmd("chatmute", "lc.chat.chatmute", (s, l, a) -> {
            m.globalMute = !m.globalMute;
            Bukkit.getOnlinePlayers().forEach(p -> m.msg(p, m.globalMute ? "chatmute.on" : "chatmute.off", "actor", s.getName()));
            m.msg(Bukkit.getConsoleSender(), m.globalMute ? "chatmute.on" : "chatmute.off", "actor", s.getName());
            m.audit(s.getName(), "chatmute", String.valueOf(m.globalMute));
        }, "mutechat", "globalmute").description("全服禁言开关"));

        m.registerCommand(new Cmd("lcchat", "lc.chat.admin", (s, l, a) -> {
            m.api().modules().reload(m.id());
            m.msg(s, "reloaded");
        }).description("重载聊天模块"));
    }


    // ---------------- 实现 ----------------

    static void sendPrivate(ChatModule m, CommandSender from, Player to, String text) {
        Player fromPlayer = from instanceof Player p ? p : null;
        if (fromPlayer != null) {
            if (fromPlayer.equals(to)) { m.msg(from, "msg.self"); return; }
            if (m.isMuted(fromPlayer)) { m.msg(from, "filter.muted"); return; }
            ChatUser targetUser = m.user(to);
            boolean staff = from.hasPermission("lc.chat.msg.bypass");
            if (targetUser.msgDisabled && !staff) { m.msg(from, "msg.disabled", "player", to.getName()); return; }
            if (targetUser.ignored.contains(fromPlayer.getUniqueId()) && !staff) { m.msg(from, "msg.ignored", "player", to.getName()); return; }
            ChatFilter.Result r = m.filter.check(fromPlayer, m.user(fromPlayer), text);
            if (r.blocked()) { m.msg(from, r.blockKey()); m.runCommands(m.filter.recordViolation(fromPlayer)); return; }
            if (r.violation()) m.runCommands(m.filter.recordViolation(fromPlayer));
            m.filter.accepted(m.user(fromPlayer), text);
            text = r.text();
            m.user(fromPlayer).lastMessaged = to.getUniqueId();
        }
        m.user(to).lastMessaged = fromPlayer == null ? null : fromPlayer.getUniqueId();

        Component body = fromPlayer != null ? m.formatter.playerText(fromPlayer, text) : ChatFormatter.legacyOrMini(text);
        String fromName = from.getName();
        from.sendMessage(ChatFormatter.render(fromPlayer, m.config().getString("private-message.to-format", "-> {target}: <message>"),
                m.vars("target", to.getName(), "sender", fromName), Placeholder.component("message", body)));
        to.sendMessage(ChatFormatter.render(to, m.config().getString("private-message.from-format", "{sender} -> : <message>"),
                m.vars("target", to.getName(), "sender", fromName), Placeholder.component("message", body)));
        if (m.config().getBoolean("private-message.sound", true)) {
            to.playSound(to.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.5f);
        }
        Component spy = ChatFormatter.render(null, m.config().getString("private-message.spy-format", "{sender} -> {target}: <message>"),
                m.vars("target", to.getName(), "sender", fromName), Placeholder.component("message", body));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(to) || p.equals(fromPlayer)) continue;
            if (p.hasPermission("lc.chat.socialspy") && m.user(p).socialSpy) p.sendMessage(spy);
        }
        m.logChat(fromPlayer, "private", fromName + " -> " + to.getName() + ": " + text);
    }

    static void staffChat(ChatModule m, CommandSender from, String text) {
        Component c = ChatFormatter.render(null, m.config().getString("staff-chat.format", "{player}: <message>"),
                m.vars("player", from.getName()), Placeholder.unparsed("message", text));
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("lc.chat.staffchat")) p.sendMessage(c);
        Bukkit.getConsoleSender().sendMessage(c);
        m.logChat(from instanceof Player p ? p : null, "staff", text);
    }

    private static void saveUser(ChatModule m, Player p, ChatUser u) {
        String plain = u.nick == null ? null : ChatFormatter.plain(ChatFormatter.nick(u.nick));
        m.store.save(u, plain);
    }

    private static Optional<UUID> resolveUuid(ChatModule m, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return Optional.of(online.getUniqueId());
        return m.api().players().uuidOf(name);
    }

    private static void mail(ChatModule m, CommandSender s, String[] a) {
        String sub = a.length == 0 ? "read" : a[0].toLowerCase();
        switch (sub) {
            case "read" -> {
                if (!(s instanceof Player p)) { m.msg(s, "only-player"); return; }
                UUID id = p.getUniqueId();
                m.runAsync(() -> {
                    try {
                        List<ChatStore.Mail> mails = m.store.mails(id);
                        m.store.markRead(id);
                        m.runTask(() -> {
                            if (mails.isEmpty()) { m.msg(p, "mail.empty"); return; }
                            m.msg(p, "mail.header", "count", mails.size());
                            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
                            for (ChatStore.Mail mail : mails) {
                                m.msg(p, "mail.entry", "time", fmt.format(new Date(mail.time())), "sender", mail.sender(),
                                        "message", mail.message());
                            }
                        });
                    } catch (SQLException e) {
                        m.logger().warning("读取邮件失败: " + e.getMessage());
                    }
                });
            }
            case "send" -> {
                if (!s.hasPermission("lc.chat.mail.send")) { m.msg(s, "usage", "usage", "无权限"); return; }
                if (a.length < 3) { usage(m, s, "/mail send <玩家> <内容>"); return; }
                Optional<UUID> id = resolveUuid(m, a[1]);
                if (id.isEmpty()) { m.msg(s, "player-not-found", "player", a[1]); return; }
                String text = join(a, 2);
                String sender = s.getName();
                m.runAsync(() -> {
                    try {
                        m.store.sendMail(id.get(), sender, text);
                        m.runTask(() -> {
                            m.msg(s, "mail.sent", "player", a[1]);
                            Player t = Bukkit.getPlayer(id.get());
                            if (t != null) m.msg(t, "mail.unread", "count", 1);
                        });
                    } catch (SQLException e) {
                        m.logger().warning("发送邮件失败: " + e.getMessage());
                    }
                });
            }
            case "sendall" -> {
                if (!s.hasPermission("lc.chat.mail.sendall")) { m.msg(s, "usage", "usage", "无权限"); return; }
                if (a.length < 2) { usage(m, s, "/mail sendall <内容>"); return; }
                String text = join(a, 1);
                String sender = s.getName();
                List<UUID> targets = new ArrayList<>();
                for (OfflinePlayer op : Bukkit.getOfflinePlayers()) targets.add(op.getUniqueId());
                m.runAsync(() -> {
                    int n = 0;
                    for (UUID t : targets) {
                        try { m.store.sendMail(t, sender, text); n++; } catch (SQLException ignored) { }
                    }
                    int count = n;
                    m.runTask(() -> m.msg(s, "mail.sent-all", "count", count));
                });
                m.audit(sender, "mail.sendall", text);
            }
            case "clear" -> {
                if (!(s instanceof Player p)) { m.msg(s, "only-player"); return; }
                UUID id = p.getUniqueId();
                m.runAsync(() -> {
                    try { m.store.clearMail(id); } catch (SQLException ignored) { }
                    m.runTask(() -> m.msg(p, "mail.cleared"));
                });
            }
            default -> m.msg(s, "mail.help");
        }
    }

    private static void nick(ChatModule m, CommandSender s, String[] a) {
        if (a.length == 0) { usage(m, s, "/nick <昵称|off> [玩家]"); return; }
        Player target;
        if (a.length >= 2) {
            if (!s.hasPermission("lc.chat.nick.others")) { m.msg(s, "usage", "usage", "无权限"); return; }
            target = Bukkit.getPlayer(a[1]);
            if (target == null) { m.msg(s, "player-not-found", "player", a[1]); return; }
        } else if (s instanceof Player p) {
            target = p;
        } else {
            m.msg(s, "only-player");
            return;
        }
        ChatUser u = m.user(target);
        if (a[0].equalsIgnoreCase("off") || a[0].equalsIgnoreCase("reset")) {
            u.nick = null;
            saveUser(m, target, u);
            m.applyNick(target, u);
            m.msg(s, "nick.reset");
            return;
        }
        String raw = a[0];
        if (!s.hasPermission("lc.chat.nick.color")) raw = raw.replaceAll("(?i)&[0-9a-fk-or]", "").replace("<", "").replace(">", "");
        String plain = ChatFormatter.plain(ChatFormatter.nick(raw));
        int min = m.config().getInt("nick.min-length", 2), max = m.config().getInt("nick.max-length", 16);
        if (plain.length() < min || plain.length() > max) { m.msg(s, "nick.too-long", "min", min, "max", max); return; }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(target) && (p.getName().equalsIgnoreCase(plain) || ChatFormatter.plain(p.displayName()).equalsIgnoreCase(plain))) {
                m.msg(s, "nick.taken");
                return;
            }
        }
        String finalRaw = raw;
        Player finalTarget = target;
        m.runAsync(() -> {
            try {
                Optional<UUID> owner = m.store.findByNick(plain);
                m.runTask(() -> {
                    if (owner.isPresent() && !owner.get().equals(finalTarget.getUniqueId())) { m.msg(s, "nick.taken"); return; }
                    u.nick = finalRaw;
                    saveUser(m, finalTarget, u);
                    m.applyNick(finalTarget, u);
                    if (finalTarget.equals(s)) m.msg(s, "nick.set", "nick", plain);
                    else m.msg(s, "nick.set-other", "player", finalTarget.getName(), "nick", plain);
                });
            } catch (SQLException e) {
                m.logger().warning("检查昵称失败: " + e.getMessage());
            }
        });
    }
}
