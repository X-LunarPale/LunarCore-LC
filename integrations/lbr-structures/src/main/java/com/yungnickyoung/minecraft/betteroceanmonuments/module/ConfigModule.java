// Paper port; original source and LGPL-3.0 notice: third-party/yungs/ocean-monuments/
package com.yungnickyoung.minecraft.betteroceanmonuments.module;

public class ConfigModule {
    public General general = new General();

    public static class General {
        public boolean disableVanillaMonuments = true;
    }
}
