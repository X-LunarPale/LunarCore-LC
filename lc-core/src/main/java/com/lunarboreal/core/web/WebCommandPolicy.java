package com.lunarboreal.core.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.command.Command;
import org.bukkit.command.FormattedCommandAlias;
import org.bukkit.command.MultipleCommandAlias;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

final class WebCommandPolicy {
    private static final String ALLOWED = "commands.web-allow";
    private static final String DENIED = "commands.web-deny";
    private static final String VANILLA_WRAPPER = "org.bukkit.craftbukkit.command.VanillaCommandWrapper";
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[\\p{javaWhitespace}\\x{00A0}]+");
    private static final Set<String> PROTECTED_ROOTS =
            Set.of("execute", "function", "schedule", "sudo", "op", "deop", "lp", "luckperms");
    private static final Set<String> LC_LABELS = Set.of("lc", "lunar", "lunarcore", "lunarboreal");
    private static final Set<String> SAFE_WEB_SUBCOMMANDS = Set.of("status", "url");

    private WebCommandPolicy() {}

    static void check(WebUserImpl user, String command, ConfigurationSection config,
                      Supplier<Function<String, Command>> commandLookup) {
        if (user.isSuper()) return;

        if (command.chars().anyMatch(c -> c < ' ' || c == 127)) throw denied();
        List<String> tokens = tokens(command);
        if (tokens.isEmpty()) throw denied();
        Function<String, Command> lookup = commandLookup.get();
        Command registered = lookup.apply(tokens.getFirst());
        if (registered == null || registered instanceof FormattedCommandAlias || registered instanceof MultipleCommandAlias) {
            throw denied();
        }

        String name = unqualified(registered.getName().toLowerCase(Locale.ROOT));
        String label = unqualified(tokens.getFirst());
        if (PROTECTED_ROOTS.contains(name) || PROTECTED_ROOTS.contains(label)) throw denied();
        if ((LC_LABELS.contains(name) || LC_LABELS.contains(label))
                && tokens.size() > 1 && tokens.get(1).equals("web")
                && (tokens.size() != 3 || !SAFE_WEB_SUBCOMMANDS.contains(tokens.get(2)))) {
            throw denied();
        }

        if (matches(config.getStringList(DENIED), tokens, registered, lookup)
                || !matches(config.getStringList(ALLOWED), tokens, registered, lookup)) {
            throw denied();
        }
    }

    static void checkConfigEdit(WebUserImpl user, ConfigurationSection original, ConfigurationSection updated) {
        if (user.isSuper()) return;
        if (!Objects.equals(original.get(ALLOWED), updated.get(ALLOWED))
                || !Objects.equals(original.get(DENIED), updated.get(DENIED))
                || !Objects.equals(original.get("web.jwt-secret"), updated.get("web.jwt-secret"))) {
            throw HttpException.forbidden("只有面板管理员可以修改命令规则或登录密钥");
        }
    }

    private static boolean matches(List<String> rules, List<String> command, Command registered,
                                   Function<String, Command> lookup) {
        for (String rule : rules) {
            List<String> prefix = tokens(rule.strip());
            if (prefix.isEmpty() || prefix.size() > command.size()) continue;
            String ruleLabel = prefix.getFirst();
            String actualLabel = command.getFirst();
            if (!ruleLabel.equals(actualLabel) && !sameHandler(ruleLabel, actualLabel, lookup.apply(ruleLabel), registered)) continue;
            if (command.subList(1, prefix.size()).equals(prefix.subList(1, prefix.size()))) return true;
        }
        return false;
    }

    private static boolean sameHandler(String rule, String actual, Command expected, Command registered) {
        if (expected == registered) return true;
        // Paper wraps vanilla Brigadier nodes in a new Bukkit command on each lookup.
        String shortName = unqualified(rule);
        String qualified = "minecraft:" + shortName;
        return expected != null && expected.getClass() == registered.getClass()
                && registered.getClass().getName().equals(VANILLA_WRAPPER)
                && ((rule.equals(shortName) && actual.equals(qualified))
                    || (rule.equals(qualified) && actual.equals(shortName)));
    }

    private static List<String> tokens(String command) {
        String label = command.toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) label = label.substring(1);
        return List.of(WORD_SEPARATOR.split(label));
    }

    private static String unqualified(String label) {
        return label.substring(label.lastIndexOf(':') + 1);
    }

    private static HttpException denied() {
        return HttpException.forbidden("当前角色无权执行这条命令");
    }
}
