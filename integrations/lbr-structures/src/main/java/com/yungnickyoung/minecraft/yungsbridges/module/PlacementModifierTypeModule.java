// Paper port; original source and LGPL-3.0 notice: third-party/yungs/bridges/
package com.yungnickyoung.minecraft.yungsbridges.module;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;
import com.yungnickyoung.minecraft.yungsbridges.YungsBridgesCommon;
import com.yungnickyoung.minecraft.yungsbridges.world.placement.BridgePlacement;
import com.yungnickyoung.minecraft.yungsbridges.world.placement.RngInitializerPlacement;

@AutoRegister(YungsBridgesCommon.MOD_ID)
public class PlacementModifierTypeModule {
    @AutoRegister("rng_initializer")
    public static MapCodec<RngInitializerPlacement> RNG_INITIALIZER = RngInitializerPlacement.CODEC;

    @AutoRegister("bridge")
    public static MapCodec<BridgePlacement> BRIDGE_PLACEMENT = BridgePlacement.CODEC;
}
