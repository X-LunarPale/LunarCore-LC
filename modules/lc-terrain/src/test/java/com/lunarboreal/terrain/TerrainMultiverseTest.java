package com.lunarboreal.terrain;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainMultiverseTest {
    public static class Options {
        NamespacedKey key;
        String name;
        World.Environment environment;
        boolean folderCheck = true;
        boolean spawnAdjust = true;
        public Options environment(World.Environment value) { environment = value; return this; }
        public Options doFolderCheck(boolean value) { folderCheck = value; return this; }
        public Options useSpawnAdjust(boolean value) { spawnAdjust = value; return this; }
    }

    public static class ModernOptions extends Options {
        public static ModernOptions worldKey(NamespacedKey key) {
            ModernOptions options = new ModernOptions();
            options.key = key;
            return options;
        }
    }

    public static class OlderOptions extends Options {
        public static OlderOptions worldName(String name) {
            OlderOptions options = new OlderOptions();
            options.name = name;
            return options;
        }
    }

    @Test void registersTheExistingDimensionByItsRealKeyWithoutFolderOrSpawnChanges() throws Exception {
        NamespacedKey key = new NamespacedKey(TerrainNamespace.CURRENT, "alpine");
        ModernOptions options = (ModernOptions) TerrainMultiverse.importOptions(ModernOptions.class, key, "alpine", World.Environment.NORMAL);
        assertEquals(key, options.key);
        assertNull(options.name);
        assertEquals(World.Environment.NORMAL, options.environment);
        assertFalse(options.folderCheck);
        assertFalse(options.spawnAdjust);
    }

    @Test void usesExistingBukkitNameForOlderMultiverseAndPreservesTheLegacyWorldKey() throws Exception {
        NamespacedKey key = new NamespacedKey(TerrainNamespace.LEGACY, "alpine");
        ModernOptions modern = (ModernOptions) TerrainMultiverse.importOptions(ModernOptions.class, key, "world/dimensions/legacy/alpine", World.Environment.NORMAL);
        assertEquals(key, modern.key);
        OlderOptions older = (OlderOptions) TerrainMultiverse.importOptions(OlderOptions.class, key, "world/dimensions/legacy/alpine", World.Environment.NORMAL);
        assertEquals("world/dimensions/legacy/alpine", older.name);
        assertFalse(older.folderCheck);
        assertFalse(older.spawnAdjust);
    }
}
