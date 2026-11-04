// Paper port; original source and LGPL-3.0 notice: third-party/yungs/desert-temples/
package com.yungnickyoung.minecraft.betterdeserttemples;

import com.yungnickyoung.minecraft.betterdeserttemples.module.ConfigModule;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BetterDesertTemplesCommon {
    public static final String MOD_ID = "betterdeserttemples";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final ConfigModule CONFIG = new ConfigModule();

    /** Global var for placing debug blocks when generating spider dungeons **/
    public static final boolean DEBUG_MODE = false;

}
