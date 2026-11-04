// Paper port; original source and LGPL-3.0 notice: third-party/yungs/end-island/
package com.yungnickyoung.minecraft.betterendisland;

import com.yungnickyoung.minecraft.betterendisland.module.ConfigModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BetterEndIslandCommon {
    public static final String MOD_ID = "betterendisland";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final ConfigModule CONFIG = new ConfigModule();

    // Mod compatibility flags
    public static boolean betterEnd;
    public static boolean endergetic;
    public static boolean moreDragonEggs;

}
