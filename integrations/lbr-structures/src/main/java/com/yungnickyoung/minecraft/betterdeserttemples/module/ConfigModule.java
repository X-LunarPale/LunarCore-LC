// Paper port; original source and LGPL-3.0 notice: third-party/yungs/desert-temples/
package com.yungnickyoung.minecraft.betterdeserttemples.module;

public class ConfigModule {
    public General general = new General();

    public static class General {
        public boolean disableVanillaPyramids = true;
        public boolean applyMiningFatigue = true;
    }
}
