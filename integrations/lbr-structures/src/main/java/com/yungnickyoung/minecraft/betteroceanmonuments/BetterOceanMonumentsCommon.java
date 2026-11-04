// Paper port; original source and LGPL-3.0 notice: third-party/yungs/ocean-monuments/
package com.yungnickyoung.minecraft.betteroceanmonuments;

import com.yungnickyoung.minecraft.betteroceanmonuments.module.ConfigModule;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BetterOceanMonumentsCommon {
    public static final String MOD_ID = "betteroceanmonuments";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final ConfigModule CONFIG = new ConfigModule();

}
