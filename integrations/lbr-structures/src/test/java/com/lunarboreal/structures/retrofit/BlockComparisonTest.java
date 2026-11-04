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
}
