// Paper port; original source and LGPL-3.0 notice: third-party/yungs/desert-temples/
package com.yungnickyoung.minecraft.betterdeserttemples.module;

import com.yungnickyoung.minecraft.betterdeserttemples.BetterDesertTemplesCommon;
import com.yungnickyoung.minecraft.betterdeserttemples.world.placement.BetterDesertTemplePlacement;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;


@AutoRegister(BetterDesertTemplesCommon.MOD_ID)
public class StructurePlacementTypeModule {
    @AutoRegister("desert_temple")
    public static final com.mojang.serialization.MapCodec<BetterDesertTemplePlacement> BETTER_DESERT_TEMPLE_PLACEMENT = BetterDesertTemplePlacement.CODEC;
}
