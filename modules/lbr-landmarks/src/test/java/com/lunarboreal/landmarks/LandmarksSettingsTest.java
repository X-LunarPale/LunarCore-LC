package com.lunarboreal.landmarks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LandmarksSettingsTest {
    @TempDir Path temp;
    @Test void textareaAllowsExplicitEmptyWorldListAndRoundTripsYaml() throws Exception {
        var empty = LandmarksSettings.fromWeb(Map.of("enabled", true, "worlds", " \n", "max-per-player", 3D));
        assertTrue(empty.worlds().isEmpty());
        assertFalse(empty.allows("world"));
        var settings = LandmarksSettings.fromWeb(Map.of("enabled", true, "worlds", "world\nworld_nether", "max-per-player", 12D));
        Path path = temp.resolve("config.yml");
        settings.save(path);
        assertEquals(settings, LandmarksSettings.load(path));
        assertEquals("world\nworld_nether", settings.web().get("worlds"));
    }
    @Test void rejectsUnexpectedKeysTypesWorldDuplicatesAndLimits() {
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of("enabled", "true", "worlds", "world", "max-per-player", 3),
                Map.of("enabled", true, "worlds", List.of("world"), "max-per-player", 3),
                Map.of("enabled", true, "worlds", "*", "max-per-player", 3),
                Map.of("enabled", true, "worlds", "world\nworld", "max-per-player", 3),
                Map.of("enabled", true, "worlds", "world", "max-per-player", 3.5),
                Map.of("enabled", true, "worlds", "world", "max-per-player", 0),
                Map.of("enabled", true, "worlds", "world", "max-per-player", 13),
                Map.of("enabled", true, "worlds", "world", "max-per-player", 3, "commands", "op")))
            assertThrows(IllegalArgumentException.class, () -> LandmarksSettings.fromWeb(invalid));
    }
    @Test void malformedYamlNeverBecomesDefaultSettings() throws Exception {
        Path file = temp.resolve("config.yml");
        Files.writeString(file, "enabled: true\nworlds: [world\nmax-per-player: 3\n");
        assertThrows(Exception.class, () -> LandmarksSettings.load(file));
    }
}
