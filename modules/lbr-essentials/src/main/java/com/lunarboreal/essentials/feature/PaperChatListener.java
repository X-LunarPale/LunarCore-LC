package com.lunarboreal.essentials.feature;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.lunarboreal.essentials.EssentialsModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/** Paper 专有事件（仅在 Paper 上注册）：聊天 AFK 检测、服务器列表隐藏隐身玩家。 */
final class PaperChatListener implements Listener {

    private final EssentialsModule m;

    PaperChatListener(EssentialsModule m) {
        this.m = m;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        m.runTask(() -> { if (p.isOnline()) m.afk().activity(p); });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent e) {
        if (!m.config().getBoolean("vanish.hide-from-server-list", true) || m.vanish().count() == 0) return;
        int online = 0;
        for (UUID u : m.vanish().all()) if (Bukkit.getPlayer(u) != null) online++;
        e.getListedPlayers().removeIf(info -> m.vanish().isVanished(info.id()));
        e.setNumPlayers(Math.max(0, e.getNumPlayers() - online));
    }
}
