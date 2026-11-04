package com.lunarboreal.structures.retrofit;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockComparisonTest {
    @Test void naturalGrowthTimersDoNotHideBuildingOrInventoryChanges() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        assertTrue(ChunkImage.sameBlock(Blocks.CACTUS.defaultBlockState().setValue(BlockStateProperties.AGE_15, 5), Blocks.CACTUS.defaultBlockState()));
        assertTrue(ChunkImage.sameBlock(Blocks.SUGAR_CANE.defaultBlockState().setValue(BlockStateProperties.AGE_15, 14), Blocks.SUGAR_CANE.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.CACTUS.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.STONE.defaultBlockState(), Blocks.SAND.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7), Blocks.WHEAT.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true), Blocks.CHEST.defaultBlockState()));
    }

    @Test void plantDriftAndRerolledMossAreNotPlayerEdits() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        assertTrue(ChunkImage.sameBlock(Blocks.SHORT_GRASS.defaultBlockState(), Blocks.FERN.defaultBlockState()));
        assertTrue(ChunkImage.sameBlock(Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertTrue(ChunkImage.sameBlock(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState()));
        assertTrue(ChunkImage.sameBlock(Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()));
        assertTrue(ChunkImage.sameBlock(Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState()));
        assertTrue(ChunkImage.sameBlock(Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.DISTANCE, 5), Blocks.OAK_LEAVES.defaultBlockState()));
        assertTrue(ChunkImage.sameBlock(Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.SOUTH, true), Blocks.OAK_FENCE.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.OAK_LOG.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertTrue(ChunkImage.sameBlock(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Blocks.IRON_ORE.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.AIR.defaultBlockState(), Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.AIR.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.COBBLESTONE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState()));
        assertFalse(ChunkImage.sameBlock(Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState()));
    }

    @Test void rerolledLootSeedIsIgnoredButALootedChestIsNot() {
        var sealed = new net.minecraft.nbt.CompoundTag();
        sealed.putString("LootTable", "minecraft:chests/jungle_temple"); sealed.putLong("LootTableSeed", 1);
        var reseeded = sealed.copy(); reseeded.putLong("LootTableSeed", 2);
        assertEquals(ChunkImage.stableBlockEntity(sealed), ChunkImage.stableBlockEntity(reseeded));
        var looted = sealed.copy(); looted.remove("LootTable"); looted.remove("LootTableSeed");
        assertNotEquals(ChunkImage.stableBlockEntity(sealed), ChunkImage.stableBlockEntity(looted));
        assertEquals(1, sealed.getLongOr("LootTableSeed", 0), "comparison must not alter the snapshot");
    }
}
