// Paper port; original source and LGPL-3.0 notice: third-party/yungs/bridges/
package com.yungnickyoung.minecraft.yungsbridges.module;

import com.mojang.serialization.MapCodec;
import com.yungnickyoung.minecraft.yungsapi.api.autoregister.AutoRegister;
import com.yungnickyoung.minecraft.yungsbridges.YungsBridgesCommon;
import com.yungnickyoung.minecraft.yungsbridges.world.feature.BridgeFeature;
import com.yungnickyoung.minecraft.yungsbridges.world.feature.MultipleAttemptSingleRandomFeature;

@AutoRegister(YungsBridgesCommon.MOD_ID)
public class FeatureModule {
    @AutoRegister("bridge")
    public static MapCodec<BridgeFeature> BRIDGE = BridgeFeature.CODEC;

    @AutoRegister("multiple_attempt_single_random")
    public static MapCodec<MultipleAttemptSingleRandomFeature> MULTIPLE_ATTEMPT_SINGLE_RANDOM = MultipleAttemptSingleRandomFeature.CODEC;
}
