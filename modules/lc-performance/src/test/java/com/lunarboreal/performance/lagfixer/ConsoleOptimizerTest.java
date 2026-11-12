package com.lunarboreal.performance.lagfixer;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleOptimizerTest {
    private static LogRecord record(String text) { return new LogRecord(Level.INFO, text); }

    private static final class Context implements PortContext {
        final Logger logger = Logger.getAnonymousLogger();
        final MemoryConfiguration config = new MemoryConfiguration();
        boolean enabled = true;
        @Override public ConfigurationSection section(String id) { assertEquals("console-filter", id); return config; }
        @Override public boolean enabled(String id) { assertEquals("console-filter", id); return enabled; }
        @Override public Logger logger() { return logger; }
        @Override public boolean allows(String id, World world) { throw new AssertionError(); }
        @Override public void listen(Listener listener) { throw new AssertionError(); }
        @Override public BukkitTask timer(Runnable r, long delay, long period) { throw new AssertionError(); }
        @Override public void later(Runnable r, long ticks) { throw new AssertionError(); }
        @Override public void count(String id) { throw new AssertionError("Logging threads must not mutate host counters"); }
    }

    @Test void disabledOrEmptyConfigurationDoesNotReplaceExistingFilter() {
        Context c = new Context();
        Filter prior = r -> true;
        c.logger.setFilter(prior);
        ConsoleOptimizer optimizer = new ConsoleOptimizer(c);
        optimizer.start();
        assertSame(prior, c.logger.getFilter());
        c.config.set("patterns", List.of("noise"));
        c.enabled = false;
        optimizer.start();
        assertSame(prior, c.logger.getFilter());
        optimizer.close();
        assertSame(prior, c.logger.getFilter());
    }

    @Test void literalsAreCaseSensitiveAndPriorFilterStillDenies() {
        Context c = new Context();
        c.config.set("patterns", List.of("[noise].*", "heartbeat"));
        Filter prior = r -> !"prior-denial".equals(r.getMessage());
        c.logger.setFilter(prior);
        ConsoleOptimizer optimizer = new ConsoleOptimizer(c);
        optimizer.start();
        Filter installed = c.logger.getFilter();
        optimizer.start();
        assertSame(installed, c.logger.getFilter());
        assertFalse(installed.isLoggable(record("before [noise].* after")));
        assertTrue(installed.isLoggable(record("noiseeeee")), "regex syntax is literal");
        assertTrue(installed.isLoggable(record("HEARTBEAT")));
        assertFalse(installed.isLoggable(record("prior-denial")));
        assertTrue(installed.isLoggable(record(null)));
        Logger other = Logger.getAnonymousLogger();
        assertNull(other.getFilter(), "other loggers are untouched");
        optimizer.close();
        optimizer.close();
        assertSame(prior, c.logger.getFilter());
        assertTrue(installed.isLoggable(record("heartbeat")), "retained filter stops suppressing after close");
        assertFalse(installed.isLoggable(record("prior-denial")), "prior policy remains chained");
    }

    @Test void closePreservesNewOwnerFilterAndRestartCannotReactivateOldRules() {
        Context c = new Context();
        c.config.set("patterns", List.of("old-rule"));
        ConsoleOptimizer optimizer = new ConsoleOptimizer(c);
        optimizer.start();
        Filter old = c.logger.getFilter();
        Filter replacement = old::isLoggable;
        c.logger.setFilter(replacement);
        optimizer.close();
        assertSame(replacement, c.logger.getFilter());
        assertTrue(replacement.isLoggable(record("old-rule")));
        c.config.set("patterns", List.of("new-rule"));
        optimizer.start();
        assertTrue(c.logger.getFilter().isLoggable(record("old-rule")));
        assertFalse(c.logger.getFilter().isLoggable(record("new-rule")));
        optimizer.close();
        assertSame(replacement, c.logger.getFilter());
    }

    @Test void patternsHaveBoundedCountAndLengthWithoutBroadeningInvalidEntries() {
        Context c = new Context();
        List<String> patterns = new ArrayList<>();
        patterns.add(" ");
        patterns.add("x".repeat(257));
        patterns.add("y".repeat(256));
        for (int i = 3; i < 33; i++) patterns.add("rule-" + i);
        c.config.set("patterns", patterns);
        List<String> accepted = ConsoleOptimizer.patterns(c.config);
        assertEquals(30, accepted.size());
        assertTrue(accepted.contains("y".repeat(256)));
        assertFalse(accepted.contains("x".repeat(256)), "overlong rules must not be truncated");
        assertFalse(accepted.contains("rule-32"));
        assertTrue(ConsoleOptimizer.patterns(null).isEmpty());
    }
}
