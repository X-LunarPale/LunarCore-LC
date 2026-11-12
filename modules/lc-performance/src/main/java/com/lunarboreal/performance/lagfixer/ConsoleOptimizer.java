/*
 * Adapted from the explicit message-pattern filtering idea in LagFixer
 * ConsoleFilterModule by lychee/lajczik, GPL-3.0.
 * https://github.com/lajczik/lagfixer/blob/0dede033f7dc2950a08f8a461bfcff84f64c7d3c/plugin/src/main/java/xyz/lychee/lagfixer/modules/ConsoleFilterModule.java
 * Modified 2026-10-19 for LC: bounded literal matching on the owning plugin's
 * JUL logger only, with reversible filter chaining. No Log4j/root filter,
 * regex evaluation, log deletion, archive management, or file interception.
 * Distributed under GNU GPL version 3; see project LICENSE and upstream notices.
 */
package com.lunarboreal.performance.lagfixer;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Filter;
import java.util.logging.Logger;

/**
 * Opt-in filtering of raw JUL message text from the current plugin logger.
 * This is not a server-wide console/Log4j filter. Patterns are case-sensitive
 * literal substrings, not regexes or MessageFormat expressions. Existing log
 * files are never read, modified or deleted. Start/close run on the server
 * thread; logging itself may happen on any thread.
 */
public final class ConsoleOptimizer implements AutoCloseable {
    static final String ID = "console-filter";
    static final int MAX_PATTERNS = 32;
    static final int MAX_PATTERN_LENGTH = 256;

    private final PortContext context;
    private Logger logger;
    private Filter previous;
    private Filter installed;
    private Activation activation;
    private static final class Activation { volatile boolean active = true; }

    public ConsoleOptimizer(PortContext context) {
        this.context = Objects.requireNonNull(context);
    }

    public void start() {
        if (installed != null || !context.enabled(ID)) return;
        List<String> patterns = patterns(context.section(ID));
        if (patterns.isEmpty()) return;
        Logger target = Objects.requireNonNull(context.logger());
        synchronized (target) {
            Filter prior = target.getFilter();
            Activation state = new Activation();
            // Capture immutable configuration and the prior filter. Do not call
            // PortContext/configuration/operation counters on logging threads.
            Filter filter = record -> {
                if (prior != null && !prior.isLoggable(record)) return false;
                if (!state.active || record == null || record.getMessage() == null) return true;
                String message = record.getMessage();
                for (String pattern : patterns) {
                    if (message.contains(pattern)) return false;
                }
                return true;
            };
            previous = prior;
            logger = target;
            installed = filter;
            activation = state;
            target.setFilter(filter);
        }
    }

    /** Invalid/blank entries are ignored, not shortened into broader rules. */
    static List<String> patterns(ConfigurationSection section) {
        if (section == null) return List.of();
        List<String> configured = section.getStringList("patterns");
        List<String> accepted = new ArrayList<>();
        for (int i = 0; i < Math.min(configured.size(), MAX_PATTERNS); i++) {
            String pattern = configured.get(i);
            if (pattern == null || pattern.isBlank() || pattern.length() > MAX_PATTERN_LENGTH) continue;
            accepted.add(pattern);
        }
        return List.copyOf(accepted);
    }

    @Override
    public void close() {
        if (activation != null) activation.active = false;
        Logger target = logger;
        if (target == null) return;
        synchronized (target) {
            // Another owner may have replaced or chained our filter. Never
            // overwrite theirs; a retained reference to ours is now pass-through.
            if (target.getFilter() == installed) target.setFilter(previous);
            installed = null;
            previous = null;
            logger = null;
        }
    }
}
