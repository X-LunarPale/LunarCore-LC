package com.lunarboreal.performance.lagfixer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldOptimizationsTest {
    @Test void windowIsBoundedResetsAndFailsOpenOnCapacity() {
        var budget = new WorldOptimizations.WindowBudget<String>();
        assertTrue(budget.allow("chunk", 1, 1, 2, 1));
        assertTrue(budget.allow("chunk", 1, 1, 2, 1));
        assertFalse(budget.allow("chunk", 1, 1, 2, 1));
        assertTrue(budget.allow("chunk", 2, 1, 2, 1));
        assertTrue(budget.allow("other chunk", 2, 1, 2, 1));
        assertEquals(1, budget.size());
        budget.clear();
        assertEquals(0, budget.size());
    }
    @Test void cooldownUsesServerTicksDoesNotExtendOnBlockedTransfers() {
        var cooldown = new WorldOptimizations.Cooldowns<String>();
        assertTrue(cooldown.allow("hopper", 10, 8, 2));
        assertFalse(cooldown.allow("hopper", 11, 8, 2));
        assertFalse(cooldown.allow("hopper", 17, 8, 2));
        assertTrue(cooldown.allow("hopper", 18, 8, 2));
        cooldown.expire(26);
        assertEquals(0, cooldown.size());
    }
    @Test void cooldownOverflowDoesNotLeakOrBlockUntrackedLocations() {
        var cooldown = new WorldOptimizations.Cooldowns<String>();
        assertTrue(cooldown.allow("one", 0, 10, 1));
        for (int i = 0; i < 1000; i++) assertTrue(cooldown.allow("overflow" + i, 1, 10, 1));
        assertEquals(1, cooldown.size());
        cooldown.clear();
        assertEquals(0, cooldown.size());
    }
    @Test void disabledPortsDoNotRegisterListenersAndTimerIsCancelledOnClose() {
        var registered = new java.util.concurrent.atomic.AtomicInteger();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var context = new PortContext() {
            public org.bukkit.configuration.ConfigurationSection section(String id) { return new org.bukkit.configuration.file.YamlConfiguration(); }
            public boolean enabled(String id) { return false; }
            public boolean allows(String id, org.bukkit.World world) { return false; }
            public void listen(org.bukkit.event.Listener listener) { registered.incrementAndGet(); }
            public org.bukkit.scheduler.BukkitTask timer(Runnable action, long delay, long period) {
                return new org.bukkit.scheduler.BukkitTask() {
                    public int getTaskId() { return 1; }
                    public org.bukkit.plugin.Plugin getOwner() { return null; }
                    public boolean isSync() { return true; }
                    public boolean isCancelled() { return cancelled.get(); }
                    public void cancel() { cancelled.set(true); }
                };
            }
            public void later(Runnable action, long ticks) { fail("No untracked delayed tasks"); }
            public void count(String id) { fail("Disabled port must not act"); }
            public java.util.logging.Logger logger() { return java.util.logging.Logger.getAnonymousLogger(); }
        };
        var port = new WorldOptimizations(context);
        port.start(); port.start();
        assertEquals(0, registered.get());
        port.close(); port.close();
        assertTrue(cancelled.get());
    }
    @Test void budgetCostSaturatesWithoutOverflow() {
        var budget = new WorldOptimizations.WindowBudget<String>();
        assertTrue(budget.allow("x", 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
        assertFalse(budget.allow("x", 0, Integer.MAX_VALUE, 100, 1));
        assertEquals(1, budget.size());
    }
}
