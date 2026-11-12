package com.lunarboreal.performance;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/** Only standard Bukkit plugin metadata/command boundaries; no LagFixer classes or code are bundled. */
final class LagFixerBridge {
    private Plugin plugin() { return Bukkit.getPluginManager().getPlugin("LagFixer"); }
    Map<String, Object> status() {
        Plugin p = plugin();
        PluginCommand cmd = p != null && p.isEnabled() ? command(p) : null;
        return Map.of("installed", p != null, "enabled", p != null && p.isEnabled(),
                "version", p == null ? "absent" : p.getDescription().getVersion(),
                "bridgeAvailable", cmd != null);
    }
    private PluginCommand command(Plugin p) {
        if (p == null || !p.isEnabled()) return null;
        // The bridge only invokes its fixed, read-only monitor subcommand, and only on the owning plugin's command.
        PluginCommand cmd = Bukkit.getPluginCommand("lagfixer:lagfixer");
        return cmd != null && cmd.getPlugin() == p ? cmd : null;
    }
    boolean monitor() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("main thread required");
        Plugin p = plugin();
        PluginCommand cmd = command(p);
        if (cmd == null) return false;
        // Fixed read-only subcommand: no caller text is ever dispatched as a console command.
        return cmd.execute(Bukkit.getConsoleSender(), "lagfixer", new String[]{"monitor"});
    }
}
