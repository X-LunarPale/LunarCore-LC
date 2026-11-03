package com.lunarboreal.core.placeholder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LbrPlaceholderRegistryTest {
    @Test void resolvesNewAndLegacyTokensWithoutPlaceholderApi() {
        var registry = new LbrPlaceholderRegistry(null);
        registry.register("core", (player, key) -> key.equals("online") ? "7" : null, this);

        assertEquals("7 / 7 / 7", registry.apply(null,
                "%lc_core_online% / %lbr_core_online% / %LC_CORE_online%"));
        assertEquals("7", registry.resolve(null, "lc_core_online"));
        assertEquals("7", registry.resolve(null, "lbr_core_online"));
        assertEquals("7", registry.resolve(null, "core_online"));
        assertEquals("%lc_unknown_key% %lbr_unknown_key% %other_value%",
                registry.apply(null, "%lc_unknown_key% %lbr_unknown_key% %other_value%"));
        assertNull(registry.apply(null, null));
        assertTrue(registry.namespaces().get("core").startsWith("%lc_core_"));
    }

    @Test void preservesLiteralReplacementLongestNamespaceAndOwnerCleanup() {
        var registry = new LbrPlaceholderRegistry(null);
        Object owner = new Object();
        registry.register("test", (player, key) -> "short", this);
        registry.register("test_long", (player, key) -> "$1\\path", owner);

        assertEquals("$1\\path $1\\path", registry.apply(null,
                "%lc_test_long_value% %lbr_test_long_value%"));
        registry.unregisterAll(owner);
        assertEquals("short short", registry.apply(null,
                "%lc_test_long_value% %lbr_test_long_value%"));
        registry.unregisterAll(this);
        assertEquals("%lc_test_value% %lbr_test_value%",
                registry.apply(null, "%lc_test_value% %lbr_test_value%"));
    }

    @Test void preservesNamespacesNamedAfterPrefixes() {
        var registry = new LbrPlaceholderRegistry(null);
        registry.register("lc", (player, key) -> "new:" + key, this);
        registry.register("lbr", (player, key) -> "old:" + key, this);

        assertEquals("new:value old:value",
                registry.apply(null, "%lc_lc_value% %lc_lbr_value%"));
        assertEquals("new:value old:value",
                registry.apply(null, "%lbr_lc_value% %lbr_lbr_value%"));
    }
}
