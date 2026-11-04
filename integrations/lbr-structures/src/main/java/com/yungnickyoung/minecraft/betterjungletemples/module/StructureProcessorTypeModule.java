// Paper port; original source and LGPL-3.0 notice: third-party/yungs/jungle-temples/
package com.yungnickyoung.minecraft.betterjungletemples.module;

import com.yungnickyoung.minecraft.betterjungletemples.BetterJungleTemplesCommon;
import com.yungnickyoung.minecraft.betterjungletemples.services.Services;
import com.yungnickyoung.minecraft.betterjungletemples.world.processor.BlastFurnaceProcessor;
import com.yungnickyoung.minecraft.betterjungletemples.world.processor.BlockReplaceProcessor;
import com.yungnickyoung.minecraft.betterjungletemples.world.processor.CaveVineDecorationProcessor;
import com.yungnickyoung.minecraft.betterjungletemples.world.processor.EmptyDispenserProcessor;
import com.yungnickyoung.minecraft.betterjungletemples.world.processor.FireballDispenserProcessor;
import com.yungnickyoung.minecraft.betterjungletemples.world.processor.PillarProcessor;
import com.yungnickyoung.minecraft.betterjungletemples.world.processor.TorchProcessor;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

@AutoRegister(BetterJungleTemplesCommon.MOD_ID)
public class StructureProcessorTypeModule {
    @AutoRegister("pillar_processor")
    public static com.mojang.serialization.MapCodec<PillarProcessor> PILLAR_PROCESSOR = PillarProcessor.CODEC;

    @AutoRegister("block_replace_processor")
    public static com.mojang.serialization.MapCodec<BlockReplaceProcessor> BLOCK_REPLACE_PROCESSOR = BlockReplaceProcessor.CODEC;

    @AutoRegister("empty_dispenser_processor")
    public static com.mojang.serialization.MapCodec<EmptyDispenserProcessor> EMPTY_DISPENSER_PROCESSOR = EmptyDispenserProcessor.CODEC;

    @AutoRegister("fireball_dispenser_processor")
    public static com.mojang.serialization.MapCodec<FireballDispenserProcessor> FIREBALL_DISPENSER_PROCESSOR = FireballDispenserProcessor.CODEC;

    @AutoRegister("cave_vine_decoration_processor")
    public static com.mojang.serialization.MapCodec<CaveVineDecorationProcessor> CAVE_VINE_DECORATION_PROCESSOR = CaveVineDecorationProcessor.CODEC;

    @AutoRegister("torch_processor")
    public static com.mojang.serialization.MapCodec<TorchProcessor> TORCH_PROCESSOR = TorchProcessor.CODEC;

    @AutoRegister("blast_furnace_processor")
    public static com.mojang.serialization.MapCodec<BlastFurnaceProcessor> BLAST_FURNACE_PROCESSOR = BlastFurnaceProcessor.CODEC;

    @AutoRegister("item_frame_processor")
    public static com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor> ITEM_FRAME_PROCESSOR = Services.PROCESSORS.itemFrameProcessorCodec();
}
