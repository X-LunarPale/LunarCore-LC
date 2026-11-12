package com.lunarboreal.ban;

import com.lunarboreal.api.service.BanService.Punishment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Optional;

/** 非 Paper 环境的聊天禁言拦截（Paper 上不注册，避免启用旧版聊天管线）。 */
@SuppressWarnings("deprecation")
final class BukkitChatListener implements Listener {

    private final BanModule m;

    BukkitChatListener(BanModule m) {
        this.m = m;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        PunishmentStore store = m.store();
        if (store == null) return;
        Optional<Punishment> mute = store.activeMute(e.getPlayer().getUniqueId());
        if (mute.isEmpty()) return;
        e.setCancelled(true);
        m.msg(e.getPlayer(), "notify.muted-chat", m.punishmentArgs(mute.get()));
    }
}
