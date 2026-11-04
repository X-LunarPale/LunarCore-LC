// Paper port; original source and LGPL-3.0 notice: third-party/yungs/witch-huts/
package com.yungnickyoung.minecraft.betterwitchhuts.module;

public class ConfigModule {
    public General general = new General();

    public static class General {
        public boolean disableVanillaWitchHuts = true;
    }
}
