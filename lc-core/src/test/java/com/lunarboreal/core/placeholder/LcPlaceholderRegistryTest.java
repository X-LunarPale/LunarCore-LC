package com.lunarboreal.core.placeholder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LcPlaceholderRegistryTest {
    @Test void resolvesLcTokensWithoutPlaceholderApi() {
        var registry = new LcPlaceholderRegistry(null);
        registry.register("core", (player, key) -> key.equals("online") ? "7" : null, this);

        assertEquals("7 / 7", registry.apply(null,
                "%lc_core_online% / %LC_CORE_online%"));
        assertEquals("7", registry.resolve(null, "lc_core_online"));
        assertEquals("7", registry.resolve(null, "core_online"));
        String oldToken = "%l" + "br_core_online%";
        assertEquals(oldToken, registry.apply(null, oldToken));
        assertEquals("%lc_unknown_key% %other_value%",
                registry.apply(null, "%lc_unknown_key% %other_value%"));
        assertNull(registry.apply(null, null));
        assertTrue(registry.namespaces().get("core").startsWith("%lc_core_"));
    }

    @Test void preservesLiteralReplacementLongestNamespaceAndOwnerCleanup() {
        var registry = new LcPlaceholderRegistry(null);
        Object owner = new Object();
        registry.register("test", (player, key) -> "short", this);
        registry.register("test_long", (player, key) -> "$1\\path", owner);

        assertEquals("$1\\path", registry.apply(null, "%lc_test_long_value%"));
        registry.unregisterAll(owner);
        assertEquals("short", registry.apply(null, "%lc_test_long_value%"));
        registry.unregisterAll(this);
        assertEquals("%lc_test_value%", registry.apply(null, "%lc_test_value%"));
    }

    @Test void preservesNamespacesNamedAfterPrefixes() {
        var registry = new LcPlaceholderRegistry(null);
        registry.register("lc", (player, key) -> "new:" + key, this);
        registry.register("other", (player, key) -> "other:" + key, this);
        assertEquals("new:value other:value",
                registry.apply(null, "%lc_lc_value% %lc_other_value%"));
    }
}
