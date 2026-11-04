// Paper port; original source and LGPL-3.0 notice: third-party/yungs/end-island/
package com.yungnickyoung.minecraft.betterendisland.module;

import com.yungnickyoung.minecraft.betterendisland.BetterEndIslandCommon;
import com.yungnickyoung.minecraft.betterendisland.world.processor.BlockReplaceProcessor;
import com.yungnickyoung.minecraft.betterendisland.world.processor.DragonEggProcessor;
import com.yungnickyoung.minecraft.betterendisland.world.processor.ObsidianProcessor;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

@AutoRegister(BetterEndIslandCommon.MOD_ID)
public class StructureProcessorTypeModule {
    @AutoRegister("block_replace_processor")
    public static com.mojang.serialization.MapCodec<BlockReplaceProcessor> BLOCK_REPLACE_PROCESSOR = BlockReplaceProcessor.CODEC;

    @AutoRegister("obsidian_processor")
    public static com.mojang.serialization.MapCodec<ObsidianProcessor> OBSIDIAN_PROCESSOR = ObsidianProcessor.CODEC;

    @AutoRegister("dragon_egg_processor")
    public static com.mojang.serialization.MapCodec<DragonEggProcessor> DRAGON_EGG_PROCESSOR = DragonEggProcessor.CODEC;
}
