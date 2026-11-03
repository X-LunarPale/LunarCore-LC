package com.lunarboreal.motd;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable, pre-parsed template. All variable values are inserted as literal components. */
final class MotdTemplate {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder().tags(TagResolver.builder()
            .resolver(StandardTags.color()).resolver(StandardTags.decorations())
            .resolver(StandardTags.gradient()).resolver(StandardTags.rainbow()).resolver(StandardTags.reset()).build()).build();
    private static final Pattern PAPI = Pattern.compile("%[^%\\s]+%");
    // One code point per placeholder survives MiniMessage's per-character gradient expansion.
    private static final Pattern PLACEHOLDERS = Pattern.compile("[\uE000-\uF8FF]");
    private final String source;
    private final Component template;
    private final Map<String, String> resolved;

    MotdTemplate(MotdSettings settings) { this(settings.line1(), settings.line2()); }
    MotdTemplate(String line1, String line2) { this(line1 + "\n" + line2); }
    MotdTemplate(String message) { this(message, Map.of()); }

    private MotdTemplate(String source, Map<String, String> values) {
        this.source = source;
        this.resolved = Map.copyOf(values);
        String marked = source.replace("{online}", "\uE000").replace("{max}", "\uE001").replace("{version}", "\uE002");
        for (Map.Entry<String, String> entry : values.entrySet()) marked = marked.replace(entry.getKey(), entry.getValue());
        template = MINI_MESSAGE.deserialize(marked);
    }

    boolean hasPlaceholders() { return PAPI.matcher(source).find(); }

    /** Called on the server thread only; the returned immutable template is safe to publish to ping threads. */
    MotdTemplate resolvePlaceholders(UnaryOperator<String> resolver) {
        Map<String, String> markers = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        Matcher matcher = PAPI.matcher(source);
        while (matcher.find()) {
            String token = matcher.group();
            if (markers.containsKey(token)) continue;
            String value = resolver.apply(token);
            if (value == null || token.equals(value)) continue;
            if (value.length() > 65536) throw new IllegalArgumentException("变量结果过长");
            String marker = String.valueOf((char) ('\uE003' + markers.size()));
            if (marker.charAt(0) > '\uF8FF') throw new IllegalArgumentException("变量过多");
            markers.put(token, marker);
            values.put(marker, value.replaceAll("[\\r\\n\\t\\u2028\\u2029]", " "));
        }
        if (markers.isEmpty()) return this;
        MotdTemplate parsed = new MotdTemplate(source, markers);
        return new MotdTemplate(source, parsed.template, values);
    }

    private MotdTemplate(String source, Component template, Map<String, String> resolved) {
        this.source = source;
        this.template = template;
        this.resolved = Map.copyOf(resolved);
    }

    Component render(int online, int max, String version) {
        return template.replaceText(builder -> builder.match(PLACEHOLDERS).replacement((match, ignored) ->
                Component.text(switch (match.group()) {
                    case "\uE000" -> Integer.toString(online);
                    case "\uE001" -> Integer.toString(max);
                    case "\uE002" -> version;
                    default -> resolved.getOrDefault(match.group(), match.group());
                })));
    }
}
