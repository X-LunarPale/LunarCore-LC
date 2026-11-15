package com.lunarboreal.terrain;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainMultiverseTest {
    public record WorldEntry(NamespacedKey key, String name) {
        public NamespacedKey getKey() { return key; }
        public String getName() { return name; }
    }

    public record Option(Object value) {
        public Object getOrNull() { return value; }
    }

    public static class Manager {
        final List<WorldEntry> unloaded;
        Manager(WorldEntry... unloaded) { this.unloaded = List.of(unloaded); }
        public Option getWorld(String name) { return new Option(null); }
        public Collection<?> getWorlds() { return List.of(); }
        public Collection<?> getLoadedWorlds() { return List.of(); }
        public Collection<?> getUnloadedWorlds() { return unloaded; }
    }

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

    @Test void findsNativeRegistrationsEvenWhenOnlyTheUnloadedListContainsThem() throws Exception {
        NamespacedKey key = new NamespacedKey(TerrainNamespace.CURRENT, "jixian");
        WorldEntry world = new WorldEntry(key, "lc_terrain_jixian");
        assertSame(world, TerrainMultiverse.find(new Manager(world), key, world.name()));
    }

    @Test void refusesToImportAgainWhenAStaleMinecraftEntryUsesTheNativeDisplayName() {
        NamespacedKey key = new NamespacedKey(TerrainNamespace.CURRENT, "jixian");
        WorldEntry conflicting = new WorldEntry(NamespacedKey.minecraft("lc_terrain_jixian"), "lc_terrain_jixian");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TerrainMultiverse.find(new Manager(conflicting), key, conflicting.name()));
        assertTrue(failure.getMessage().contains("minecraft:lc_terrain_jixian"));
        assertTrue(failure.getMessage().contains("lc_terrain:jixian"));
    }
}
