package com.lunarboreal.ban;

import com.lunarboreal.api.service.BanService.Punishment;
import com.lunarboreal.api.service.BanService.Type;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Optional;

/** 登录拦截与禁言命令拦截（纯 Bukkit API）。 */
final class BanListener implements Listener {

    private final BanModule m;

    BanListener(BanModule m) {
        this.m = m;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        PunishmentStore store = m.store();
        if (store == null) return;
        String ip = e.getAddress() == null ? null : e.getAddress().getHostAddress();
        boolean cacheReady = store.awaitLoaded(m.config().getLong("login-wait-millis", 5000));

        // 多服模式下缓存未命中时再查一次数据库（预登录本身在异步线程，阻塞查询可接受）
        boolean multi = m.multiServer();
        Optional<Punishment> ban = cacheReady ? store.activeBan(e.getUniqueId()) : Optional.empty();
        if (ban.isEmpty() && (!cacheReady || multi)) ban = store.queryActiveSync(Type.BAN, "uuid", e.getUniqueId().toString());
        if (ban.isPresent()) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, m.screen(ban.get()));
            return;
        }
        if (ip != null) {
            Optional<Punishment> ipBan = cacheReady ? store.activeIpBan(ip) : Optional.empty();
            if (ipBan.isEmpty() && (!cacheReady || multi)) ipBan = store.queryActiveSync(Type.IPBAN, "ip", ip);
            if (ipBan.isPresent()) {
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, m.screen(ipBan.get()));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        PunishmentStore store = m.store();
        if (store == null) return;
        Player p = e.getPlayer();
        Optional<Punishment> mute = store.activeMute(p.getUniqueId());
        if (mute.isEmpty()) return;
        String msg = e.getMessage();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String label = msg.split("\\s+", 2)[0];
        if (m.isMutedCommand(label)) {
            e.setCancelled(true);
            m.msg(p, "notify.muted-command", m.punishmentArgs(mute.get()));
        }
    }
}
