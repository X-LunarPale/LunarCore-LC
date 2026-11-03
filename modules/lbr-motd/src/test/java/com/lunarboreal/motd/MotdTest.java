package com.lunarboreal.motd;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MotdTest {
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    @Test void rendersTwoLinesWithLiveCountsAndPreservesBrand() {
        var template = new MotdTemplate(new MotdSettings(true, MotdSettings.DEFAULT_LINE1, MotdSettings.DEFAULT_LINE2));
        assertEquals("Lunazure 琉月·冰澜\n共赴冰澜之境 ｜ 12/100 人在线 ｜ 26.3", plain(template.render(12, 100, "26.3")));
        assertTrue(plain(template.render(13, 100, "26.3")).contains("13/100"));
    }

    @Test void placeholderValuesAreLiteralAndNotRecursivelyExpanded() {
        var template = new MotdTemplate(new MotdSettings(true, "<green>{version}</green>", "{online}/{max}"));
        assertEquals("<red>{online}</red>\n2/20", plain(template.render(2, 20, "<red>{online}</red>")));
        Component result = new MotdTemplate(new MotdSettings(true, "<green>{online}</green>", "" )).render(2, 20, "26.3");
        assertTrue(hasColor(result, NamedTextColor.GREEN));
        assertFalse(hasColor(template.render(2, 20, "<red>text</red>"), NamedTextColor.RED));
    }

    private static boolean hasColor(Component value, NamedTextColor color) {
        return color.equals(value.color()) || value.children().stream().anyMatch(child -> hasColor(child, color));
    }

    @Test void replacesPlaceholdersWithinGradients() {
        var template = new MotdTemplate(new MotdSettings(true, "<gradient:red:blue>{online}/{max}</gradient>", ""));
        assertEquals("2/20\n", plain(template.render(2, 20, "26.3")));
    }

    @Test void cannotIntroduceExtraMotdLinesThroughMiniMessage() {
        var template = new MotdTemplate(new MotdSettings(true, "first<newline>injected", "second"));
        assertEquals(1, plain(template.render(0, 20, "26.3")).chars().filter(c -> c == '\n').count());
    }

    @Test void resolvesPapiInsideGradientsOncePerUniqueToken() {
        var template = new MotdTemplate("<gradient:red:blue>%test_value%/%test_value%</gradient>", "{online}/{max}");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var resolved = template.resolvePlaceholders(token -> { calls.incrementAndGet(); return "12"; });
        assertEquals(1, calls.get());
        assertEquals("12/12\n2/20", plain(resolved.render(2, 20, "26.3")));
        assertEquals("12/12\n3/20", plain(resolved.render(3, 20, "26.3")));
        assertTrue(plain(template.render(2, 20, "26.3")).contains("%test_value%"));
    }

    @Test void papiValuesCannotInjectMiniMessageLinesOrRecursiveVariables() {
        var template = new MotdTemplate("<green>%test_value%</green>", "second");
        var resolved = template.resolvePlaceholders(token -> "<red>{online}%other_value%</red>\nthird");
        var result = resolved.render(2, 20, "26.3");
        assertEquals("<red>{online}%other_value%</red> third\nsecond", plain(result));
        assertFalse(hasColor(result, NamedTextColor.RED));
        assertTrue(hasColor(result, NamedTextColor.GREEN));
    }

    @Test void missingPapiKeepsUnknownTokensAndOversizedValuesFailBeforePublication() {
        var template = new MotdTemplate("%missing_value%", "second");
        assertEquals("%missing_value%\nsecond", plain(template.resolvePlaceholders(token -> token).render(0, 20, "26.3")));
        assertThrows(IllegalArgumentException.class,
                () -> template.resolvePlaceholders(token -> "x".repeat(65537)));
    }

    @Test void validatesFullBodyTypesAndTextBeforeReplacement() {
        Map<String, Object> valid = Map.of("enabled", true, "line1", "", "line2", "<aqua>欢迎");
        assertEquals(valid, MotdSettings.from(valid).webValues());
        assertThrows(IllegalArgumentException.class, () -> MotdSettings.from(Map.of("enabled", true, "line1", "x")));
        Map<String, Object> changed = new HashMap<>(valid);
        changed.put("enabled", "true");
        assertThrows(IllegalArgumentException.class, () -> MotdSettings.from(changed));
        changed.put("enabled", true); changed.put("extra", "unexpected");
        assertThrows(IllegalArgumentException.class, () -> MotdSettings.from(changed));
        assertThrows(IllegalArgumentException.class, () -> new MotdSettings(true, "x".repeat(513), ""));
        assertDoesNotThrow(() -> new MotdSettings(true, "x".repeat(512), ""));
        for (String bad : new String[]{"a\nb", "a\rb", "a\tb", "a\u0000b", "a\u2028b"})
            assertThrows(IllegalArgumentException.class, () -> new MotdSettings(true, bad, ""));
    }
}
