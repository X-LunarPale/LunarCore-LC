// Paper port; original source and LGPL-3.0 notice: third-party/yungs/fortresses/
package com.yungnickyoung.minecraft.betterfortresses.module;

public class ConfigModule {
    public General general = new General();

    public static class General {
        public boolean disableVanillaFortresses = true;
    }
}
