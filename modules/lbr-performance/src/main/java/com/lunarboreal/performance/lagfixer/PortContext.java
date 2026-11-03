package com.lunarboreal.performance.lagfixer;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import java.util.logging.Logger;

/** LBR-owned lifecycle boundary for GPLv3 LagFixer ports. All calls run on the server thread. */
public interface PortContext {
    ConfigurationSection section(String id);
    boolean enabled(String id);
    boolean allows(String id, World world);
    void listen(Listener listener);
    BukkitTask timer(Runnable action, long delay, long period);
    void later(Runnable action, long ticks);
    void count(String id);
    Logger logger();
}
