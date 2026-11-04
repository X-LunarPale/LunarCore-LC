// Paper port; original source and LGPL-3.0 notice: third-party/yungs/fortresses/
package com.yungnickyoung.minecraft.betterfortresses.module;

import com.yungnickyoung.minecraft.betterfortresses.BetterFortressesCommon;
import com.yungnickyoung.minecraft.betterfortresses.services.Services;
import com.yungnickyoung.minecraft.betterfortresses.world.processor.BridgeArchProcessor;
import com.yungnickyoung.minecraft.betterfortresses.world.processor.LiquidBlockProcessor;
import com.yungnickyoung.minecraft.betterfortresses.world.processor.NetherWartProcessor;
import com.yungnickyoung.minecraft.betterfortresses.world.processor.PillarProcessor;
import com.yungnickyoung.minecraft.betterfortresses.world.processor.RedSandstoneStairsProcessor;
import com.yungnickyoung.minecraft.betterfortresses.world.processor.StairPillarProcessor;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

@AutoRegister(BetterFortressesCommon.MOD_ID)
public class StructureProcessorTypeModule {
    @AutoRegister("pillar_processor")
    public static com.mojang.serialization.MapCodec<PillarProcessor> PILLAR_PROCESSOR = PillarProcessor.CODEC;

    @AutoRegister("stair_pillar_processor")
    public static com.mojang.serialization.MapCodec<StairPillarProcessor> STAIR_PILLAR_PROCESSOR = StairPillarProcessor.CODEC;

    @AutoRegister("red_sandstone_stairs_processor")
    public static com.mojang.serialization.MapCodec<RedSandstoneStairsProcessor> RED_SANDSTONE_STAIRS_PROCESSOR = RedSandstoneStairsProcessor.CODEC;

    @AutoRegister("bridge_arch_processor")
    public static com.mojang.serialization.MapCodec<BridgeArchProcessor> BRIDGE_ARCH_PROCESSOR = BridgeArchProcessor.CODEC;

    @AutoRegister("liquid_block_processor")
    public static com.mojang.serialization.MapCodec<LiquidBlockProcessor> LIQUID_BLOCK_PROCESSOR = LiquidBlockProcessor.CODEC;

    @AutoRegister("nether_wart_processor")
    public static com.mojang.serialization.MapCodec<NetherWartProcessor> NETHER_WART_PROCESSOR = NetherWartProcessor.CODEC;

    @AutoRegister("item_frame_processor")
    public static com.mojang.serialization.MapCodec<StructureProcessor> ITEM_FRAME_PROCESSOR = Services.PROCESSORS.itemFrameProcessorCodec();
}
