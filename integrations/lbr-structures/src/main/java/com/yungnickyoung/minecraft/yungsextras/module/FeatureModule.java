// Paper port; original source and LGPL-3.0 notice: third-party/yungs/extras/
package com.yungnickyoung.minecraft.yungsextras.module;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;
import com.yungnickyoung.minecraft.yungsextras.YungsExtrasCommon;
import com.yungnickyoung.minecraft.yungsextras.world.feature.desert.*;
import com.yungnickyoung.minecraft.yungsextras.world.feature.swamp.*;

@AutoRegister(YungsExtrasCommon.MOD_ID)
public class FeatureModule {
    @AutoRegister("desert_well")
    public static MapCodec<DesertWellFeature> DESERT_WELL = DesertWellFeature.CODEC;

    @AutoRegister("desert_obelisk")
    public static MapCodec<DesertObeliskFeature> DESERT_OBELISK = DesertObeliskFeature.CODEC;

    @AutoRegister("desert_giant_torch")
    public static MapCodec<DesertGiantTorchFeature> DESERT_GIANT_TORCH = DesertGiantTorchFeature.CODEC;

    @AutoRegister("desert_ruins_0")
    public static MapCodec<DesertSmallRuinsFeature> DESERT_RUINS_0 = DesertSmallRuinsFeature.CODEC;

    @AutoRegister("desert_chillzone")
    public static MapCodec<ChillzoneDesertFeature> DESERT_CHILLZONE = ChillzoneDesertFeature.CODEC;

    @AutoRegister("swamp_pillar")
    public static MapCodec<SwampPillarFeature> SWAMP_PILLAR = SwampPillarFeature.CODEC;

    @AutoRegister("swamp_ogre")
    public static MapCodec<SwampOgreFeature> SWAMP_OGRE = SwampOgreFeature.CODEC;

    @AutoRegister("swamp_cubby")
    public static MapCodec<SwampCubbyFeature> SWAMP_CUBBY = SwampCubbyFeature.CODEC;

    @AutoRegister("swamp_arch")
    public static MapCodec<SwampArchFeature> SWAMP_ARCH = SwampArchFeature.CODEC;

    @AutoRegister("swamp_double_arch")
    public static MapCodec<SwampDoubleArchFeature> SWAMP_DOUBLE_ARCH = SwampDoubleArchFeature.CODEC;

    @AutoRegister("swamp_church")
    public static MapCodec<SwampChurchFeature> SWAMP_CHURCH = SwampChurchFeature.CODEC;
}
