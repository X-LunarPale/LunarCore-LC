package com.lunarboreal.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/** Paper 聊天处理（AsyncChatEvent + ChatRenderer）。仅在 Paper 上注册。 */
final class PaperChatListener implements Listener {

    private final ChatModule m;

    PaperChatListener(ChatModule module) {
        this.m = module;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        ChatUser user = m.user(p);
        String raw = ChatFormatter.plain(e.message());

        // 管理频道
        if (user.staffChat && p.hasPermission("lbr.chat.staffchat")) {
            e.setCancelled(true);
            ChatCommands.staffChat(m, p, raw);
            return;
        }
        if (m.globalMute && !p.hasPermission("lbr.chat.chatmute.bypass")) {
            e.setCancelled(true);
            m.msg(p, "filter.global-muted");
            return;
        }
        ChatFilter.Result r = m.filter.check(p, user, raw);
        if (r.blocked()) {
            e.setCancelled(true);
            m.msg(p, r.blockKey());
            m.runCommands(m.filter.recordViolation(p));
            return;
        }
        if (r.violation()) m.runCommands(m.filter.recordViolation(p));
        m.filter.accepted(user, raw);

        // 屏蔽：从接收者中移除屏蔽了发送者的玩家
        e.viewers().removeIf(a -> a instanceof Player v && m.store.peek(v.getUniqueId())
                .map(u -> u.ignored.contains(p.getUniqueId())).orElse(false));

        if (!m.config().getBoolean("format.enabled", true)) {
            e.message(Component.text(r.text()));
            m.logChat(p, "public", r.text());
            return;
        }
        Component body = m.formatter.playerText(p, r.text());
        List<Player> mentioned = new ArrayList<>();
        body = m.formatter.highlightMentions(body, r.text(), mentioned);
        e.message(body);
        Component formatted = m.formatter.formatChat(p, body);
        e.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> formatted));
        m.sendMentionSounds(mentioned);
        m.logChat(p, "public", r.text());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAiChat(AsyncChatEvent event) {
        if (!m.aiChat.acceptsPublicChat()) return;
        m.aiChat.onPublicChat(event.getPlayer(), ChatFormatter.plain(event.message()));
    }
}
