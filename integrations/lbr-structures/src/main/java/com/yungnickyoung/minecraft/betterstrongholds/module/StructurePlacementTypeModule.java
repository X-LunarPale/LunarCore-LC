// Paper port; original source and LGPL-3.0 notice: third-party/yungs/strongholds/
package com.yungnickyoung.minecraft.betterstrongholds.module;

import com.yungnickyoung.minecraft.betterstrongholds.BetterStrongholdsCommon;
import com.yungnickyoung.minecraft.betterstrongholds.world.placement.BetterStrongholdsPlacement;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;


@AutoRegister(BetterStrongholdsCommon.MOD_ID)
public class StructurePlacementTypeModule {
    @AutoRegister("stronghold")
    public static final com.mojang.serialization.MapCodec<BetterStrongholdsPlacement> BETTER_STRONGHOLD_PLACEMENT = BetterStrongholdsPlacement.CODEC;
}
