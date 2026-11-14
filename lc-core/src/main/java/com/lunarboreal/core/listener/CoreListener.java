package com.lunarboreal.core.listener;

import com.lunarboreal.core.LunarCorePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * core 级监听：
 * <ul>
 *   <li>ServerLoadEvent：重新抢占 override 命令、同步命令树、挂接 PlaceholderAPI；</li>
 *   <li>PluginEnable/Disable：PAPI 动态挂接；外部插件卸载时自动注销其注册的命令/服务/web/变量/语言包。</li>
 * </ul>
 */
public final class CoreListener implements Listener {

    private final LunarCorePlugin plugin;

    public CoreListener(LunarCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent e) {
        plugin.commandRegistry().markServerLoaded();
        plugin.commandRegistry().reapplyOverrides();
        plugin.commandRegistry().syncNow();
        plugin.placeholders().hookPapi();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent e) {
        if (e.getPlugin().getName().equals("PlaceholderAPI")) {
            plugin.placeholders().hookPapi();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent e) {
        Plugin other = e.getPlugin();
        if (other == plugin) return;
        if (other.getName().equals("PlaceholderAPI")) {
            plugin.placeholders().dropPapi();
        }
        plugin.unregisterExternal(other);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.sounds().forget(event.getPlayer().getUniqueId());
    }
}
