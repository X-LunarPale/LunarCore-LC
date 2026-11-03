package com.lunarboreal.ban;

import com.lunarboreal.api.service.BanService.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Optional;

/** Paper 聊天禁言拦截（仅在 Paper 上注册，避免非 Paper 环境加载 io.papermc 类）。 */
final class PaperChatListener implements Listener {

    private final BanModule m;

    PaperChatListener(BanModule m) {
        this.m = m;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        PunishmentStore store = m.store();
        if (store == null) return;
        Optional<Punishment> mute = store.activeMute(e.getPlayer().getUniqueId());
        if (mute.isEmpty()) return;
        e.setCancelled(true);
        m.msg(e.getPlayer(), "notify.muted-chat", m.punishmentArgs(mute.get()));
    }
}
