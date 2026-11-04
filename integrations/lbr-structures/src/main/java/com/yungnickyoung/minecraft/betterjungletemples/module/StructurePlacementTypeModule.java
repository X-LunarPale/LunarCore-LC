// Paper port; original source and LGPL-3.0 notice: third-party/yungs/jungle-temples/
package com.yungnickyoung.minecraft.betterjungletemples.module;

import com.yungnickyoung.minecraft.betterjungletemples.BetterJungleTemplesCommon;
import com.yungnickyoung.minecraft.betterjungletemples.world.placement.BetterJungleTemplePlacement;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;


@AutoRegister(BetterJungleTemplesCommon.MOD_ID)
public class StructurePlacementTypeModule {
    @AutoRegister("jungle_temple")
    public static final com.mojang.serialization.MapCodec<BetterJungleTemplePlacement> BETTER_JUNGLE_TEMPLE_PLACEMENT = BetterJungleTemplePlacement.CODEC;
}
