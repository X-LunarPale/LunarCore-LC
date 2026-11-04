// Paper port; original source and LGPL-3.0 notice: third-party/yungs/witch-huts/
package com.yungnickyoung.minecraft.betterwitchhuts;

import com.yungnickyoung.minecraft.betterwitchhuts.module.ConfigModule;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BetterWitchHutsCommon {
    public static final String MOD_ID = "betterwitchhuts";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final ConfigModule CONFIG = new ConfigModule();

}
