// Paper port; original source and LGPL-3.0 notice: third-party/yungs/dungeons/
package com.yungnickyoung.minecraft.betterdungeons;

import com.yungnickyoung.minecraft.betterdungeons.module.ConfigModule;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BetterDungeonsCommon {
    public static final String MOD_ID = "betterdungeons";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final ConfigModule CONFIG = new ConfigModule();

    /** Global var for placing debug blocks when generating spider dungeons **/
    public static final boolean DEBUG_MODE = false;

}
