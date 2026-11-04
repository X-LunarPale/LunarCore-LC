package com.lunarboreal.terrain.nms;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainBlendingHooksTest {
    private static final ResourceKey<Level> TERRAIN = ResourceKey.create(
            Registries.DIMENSION, Identifier.parse("lbr_terrain:alpine"));

    @Test void installsAndRemovesTheNativeOldChunkPolicy() throws Exception {
        TerrainBlendingHooks.install();
        var storage = new RegionStorageInfo("test", TERRAIN, "chunk");
        CompoundTag tag = fullChunk();
        try {
            assertEquals(true, NativeTectonicClimate.hook("isOldChunk",
                    new Class<?>[]{Object.class, Object.class, boolean.class}, storage, tag, false));
            TerrainBlendingHooks.stamp(TERRAIN, tag);
            assertEquals(false, NativeTectonicClimate.hook("isOldChunk",
                    new Class<?>[]{Object.class, Object.class, boolean.class}, storage, tag, false));
        } finally {
            TerrainBlendingHooks.uninstall();
        }
        assertEquals(false, NativeTectonicClimate.hook("isOldChunk",
                new Class<?>[]{Object.class, Object.class, boolean.class}, storage, fullChunk(), false));
    }

    @Test void oldTerrainChunksRebuildTheirHeightAndLightDataWithinTheirSavedHeightRange() {
        CompoundTag tag = fullChunk();
        ListTag sections = new ListTag();
        for (int y : new int[]{-8, 0, 31}) {
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) y);
            section.put("block_states", new CompoundTag());
            sections.add(section);
        }
        tag.put("sections", sections);

        TerrainBlendingHooks.prepare(TERRAIN, tag);

        CompoundTag blending = tag.getCompoundOrEmpty("blending_data");
        assertEquals(-8, blending.getIntOr("min_section", 0));
        assertEquals(32, blending.getIntOr("max_section", 0));
        assertFalse(tag.contains("Heightmaps"));
        assertFalse(tag.contains("isLightOn"));
        var storage = new RegionStorageInfo("test", TERRAIN, "chunk");
        assertEquals(true, TerrainBlendingHooks.isOldChunk(new Object[]{storage, tag, false}));
        TerrainBlendingHooks.stamp(TERRAIN, tag);
        assertEquals(false, TerrainBlendingHooks.isOldChunk(new Object[]{storage, tag, false}));
        assertEquals(true, TerrainBlendingHooks.isOldChunk(new Object[]{storage, tag, true}));
    }

    @Test void neverChangesPrimaryWorldChunksOrReblendsCurrentTerrainChunks() {
        CompoundTag vanilla = fullChunk();
        CompoundTag expected = vanilla.copy();
        TerrainBlendingHooks.prepare(Level.OVERWORLD, vanilla);
        TerrainBlendingHooks.stamp(Level.OVERWORLD, vanilla);
        assertEquals(expected, vanilla);
        assertEquals(false, TerrainBlendingHooks.isOldChunk(new Object[]{
                new RegionStorageInfo("test", Level.OVERWORLD, "chunk"), vanilla, false}));

        CompoundTag terrain = fullChunk();
        TerrainBlendingHooks.stamp(TERRAIN, terrain);
        expected = terrain.copy();
        TerrainBlendingHooks.prepare(TERRAIN, terrain);
        assertEquals(expected, terrain);
    }

    @Test void chunksBeforeNoiseGenerationAreNotMarkedForBlending() {
        CompoundTag tag = fullChunk();
        tag.putString("Status", "minecraft:biomes");
        CompoundTag expected = tag.copy();
        TerrainBlendingHooks.prepare(TERRAIN, tag);
        assertEquals(expected, tag);
    }

    private static CompoundTag fullChunk() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        tag.put("Heightmaps", new CompoundTag());
        tag.putBoolean("isLightOn", true);
        return tag;
    }
}
