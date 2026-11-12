package com.lunarboreal.journal;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JournalSettingsTest {

    @Test void emptyWhitelistStaysEmptyAndPartialUpdatesPreserveOtherFields() {
        var original = JournalSettings.defaults();
        var updated = original.updated(Map.of("worlds", "", "sample_seconds", 2.0));
        assertEquals(Set.of(), updated.worlds());
        assertEquals(2, updated.sampleSeconds());
        assertEquals(original.recordFishing(), updated.recordFishing());
        assertFalse(original.worlds().isEmpty());
    }

    @Test void webYamlRoundTripPreservesWhitelistAndDisabledOptions() {
        var settings = JournalSettings.defaults().updated(Map.of(
                "worlds", "world\nworld_nether\nworld\n", "enabled", false,
                "record_advancements", false, "notify_discoveries", true));
        assertEquals(List.of("world", "world_nether"), List.copyOf(settings.worlds()));
        assertEquals(settings, JournalSettings.fromYaml(settings.toYaml()));
    }

    @Test void malformedUpdatesAreRejectedWithoutMutatingCurrentSnapshot() {
        var original = JournalSettings.defaults();
        for (Object invalid : List.of(1, 61, 2.5, Double.NaN, Double.POSITIVE_INFINITY, "5"))
            assertThrows(IllegalArgumentException.class, () -> original.updated(Map.of("sample_seconds", invalid)));
        assertThrows(IllegalArgumentException.class, () -> original.updated(Map.of("enabled", "true")));
        assertThrows(IllegalArgumentException.class, () -> original.updated(Map.of("unknown", true)));
        assertEquals(5, original.sampleSeconds());
        assertTrue(original.enabled());
    }

    @Test void whitelistIsBoundedAndRejectsControls() {
        var original = JournalSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> original.updated(Map.of("worlds", "w".repeat(129))));
        assertThrows(IllegalArgumentException.class, () -> original.updated(Map.of("worlds", "world\u0000")));
        String many = String.join("\n", java.util.stream.IntStream.range(0, 65).mapToObj(i -> "world" + i).toList());
        assertThrows(IllegalArgumentException.class, () -> original.updated(Map.of("worlds", many)));
        assertThrows(UnsupportedOperationException.class, () -> original.worlds().add("world_new"));
    }

    @Test void wrongYamlTypesAreRejected() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("worlds", "world");
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.fromYaml(yaml));
        yaml.set("worlds", List.of());
        yaml.set("record-fishing", "false");
        assertThrows(IllegalArgumentException.class, () -> JournalSettings.fromYaml(yaml));
    }

    @Test void onlyVanillaNonRecipeAdvancementsAreRecorded() {
        assertTrue(JournalEntry.vanillaAdvancement("minecraft", "adventure/adventuring_time"));
        assertFalse(JournalEntry.vanillaAdvancement("minecraft", "recipes/tools/stone_pickaxe"));
        assertFalse(JournalEntry.vanillaAdvancement("custom", "story/root"));
    }

    @Test void strictPaginationRejectsInvalidInputInsteadOfFallingBack() {
        assertEquals(20, JournalWeb.positive(null, 20, 100, "size"));
        assertEquals(100, JournalWeb.positive("100", 20, 100, "size"));
        for (String invalid : List.of("", "0", "-1", "1.5", "NaN", "101", "999999999999", " 1", "+1"))
            assertThrows(com.lunarboreal.api.web.HttpException.class,
                    () -> JournalWeb.positive(invalid, 20, 100, "size"));
    }
}
