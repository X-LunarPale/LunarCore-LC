package com.lunarboreal.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.List;

/** 非 Paper（Spigot/Bukkit）回退：使用 AsyncPlayerChatEvent + 旧式格式字符串。 */
@SuppressWarnings("deprecation")
final class BukkitChatListener implements Listener {

    private final ChatModule m;

    BukkitChatListener(ChatModule module) {
        this.m = module;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        ChatUser user = m.user(p);
        String raw = e.getMessage();
        if (user.staffChat && p.hasPermission("lc.chat.staffchat")) {
            e.setCancelled(true);
            ChatCommands.staffChat(m, p, raw);
            return;
        }
        if (m.globalMute && !p.hasPermission("lc.chat.chatmute.bypass")) {
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
        e.setMessage(r.text());
        e.getRecipients().removeIf(v -> m.store.peek(v.getUniqueId()).map(u -> u.ignored.contains(p.getUniqueId())).orElse(false));
        if (m.config().getBoolean("format.enabled", true)) {
            Component body = p.hasPermission("lc.chat.color")
                    ? LegacyComponentSerializer.legacyAmpersand().deserialize(r.text()) : Component.text(r.text());
            List<Player> mentioned = new ArrayList<>();
            body = m.formatter.highlightMentions(body, r.text(), mentioned);
            String legacy = LegacyComponentSerializer.legacySection().serialize(m.formatter.formatChat(p, body));
            e.setFormat(legacy.replace("%", "%%"));
            m.sendMentionSounds(mentioned);
        } else {
            e.setMessage(r.text());
        }
        m.logChat(p, "public", r.text());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAiChat(AsyncPlayerChatEvent event) {
        if (!m.aiChat.acceptsPublicChat()) return;
        m.aiChat.onPublicChat(event.getPlayer(), event.getMessage());
    }
}
