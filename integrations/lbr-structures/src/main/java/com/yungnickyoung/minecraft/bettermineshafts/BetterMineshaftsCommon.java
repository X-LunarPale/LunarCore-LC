// Paper port; original source and LGPL-3.0 notice: third-party/yungs/mineshafts/
package com.yungnickyoung.minecraft.bettermineshafts;

import com.yungnickyoung.minecraft.bettermineshafts.module.ConfigModule;
import io.netty.util.internal.ConcurrentSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

public class BetterMineshaftsCommon {
    public static final String MOD_ID = "bettermineshafts";
    public static final String MOD_NAME = "YUNG's Better Mineshafts";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final ConfigModule CONFIG = new ConfigModule();

    /** Debug info **/
    public static ConcurrentSet<Integer> surfaceEntrances = new ConcurrentSet<>();
    public static AtomicInteger count = new AtomicInteger(0);
    public static final boolean DEBUG_LOG = false;

}
