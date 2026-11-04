// Paper port; original source and LGPL-3.0 notice: third-party/yungs/strongholds/
package com.yungnickyoung.minecraft.betterstrongholds.module;

import com.yungnickyoung.minecraft.betterstrongholds.BetterStrongholdsCommon;
import com.yungnickyoung.minecraft.betterstrongholds.services.Services;
import com.yungnickyoung.minecraft.betterstrongholds.world.processor.*;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;

@AutoRegister(BetterStrongholdsCommon.MOD_ID)
public class StructureProcessorTypeModule {
    @AutoRegister("ruin_processor")
    public static com.mojang.serialization.MapCodec<RuinProcessor> RUIN_PROCESSOR = RuinProcessor.CODEC;

    @AutoRegister("banner_processor")
    public static com.mojang.serialization.MapCodec<BannerProcessor> BANNER_PROCESSOR = BannerProcessor.CODEC;

    @AutoRegister("ore_processor")
    public static com.mojang.serialization.MapCodec<OreProcessor> ORE_PROCESSOR = OreProcessor.CODEC;

    @AutoRegister("rare_block_processor")
    public static com.mojang.serialization.MapCodec<RareBlockProcessor> RARE_BLOCK_PROCESSOR = RareBlockProcessor.CODEC;

    @AutoRegister("redstone_processor")
    public static com.mojang.serialization.MapCodec<RedstoneProcessor> REDSTONE_PROCESSOR = RedstoneProcessor.CODEC;

    @AutoRegister("leg_processor")
    public static com.mojang.serialization.MapCodec<LegProcessor> LEG_PROCESSOR = LegProcessor.CODEC;

    @AutoRegister("end_portal_frame_processor")
    public static com.mojang.serialization.MapCodec<EndPortalFrameProcessor> END_PORTAL_FRAME_PROCESSOR = EndPortalFrameProcessor.CODEC;

    @AutoRegister("armorstand_processor")
    public static com.mojang.serialization.MapCodec<StructureProcessor> ARMORSTAND_PROCESSOR = Services.PROCESSORS.armorStandProcessorCodec();

    @AutoRegister("itemframe_processor")
    public static com.mojang.serialization.MapCodec<StructureProcessor> ITEMFRAME_PROCESSOR = Services.PROCESSORS.itemFrameProcessorCodec();
}
