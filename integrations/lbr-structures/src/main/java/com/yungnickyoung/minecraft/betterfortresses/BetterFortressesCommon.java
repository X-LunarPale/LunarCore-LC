// Paper port; original source and LGPL-3.0 notice: third-party/yungs/fortresses/
package com.yungnickyoung.minecraft.betterfortresses;

import com.yungnickyoung.minecraft.betterfortresses.module.ConfigModule;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BetterFortressesCommon {
    public static final String MOD_ID = "betterfortresses";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final ConfigModule CONFIG = new ConfigModule();


    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
