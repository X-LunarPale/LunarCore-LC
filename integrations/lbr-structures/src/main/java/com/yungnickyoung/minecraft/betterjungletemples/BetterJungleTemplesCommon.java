// Paper port; original source and LGPL-3.0 notice: third-party/yungs/jungle-temples/
package com.yungnickyoung.minecraft.betterjungletemples;

import com.yungnickyoung.minecraft.betterjungletemples.module.ConfigModule;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BetterJungleTemplesCommon {
    public static final String MOD_ID = "betterjungletemples";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final ConfigModule CONFIG = new ConfigModule();

}
