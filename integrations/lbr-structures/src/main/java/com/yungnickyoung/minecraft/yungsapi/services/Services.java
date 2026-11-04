package com.yungnickyoung.minecraft.yungsapi.services;

/** Paper replacement for the loader-specific service locator. */
public final class Services {
    private Services() {}
    public static final IStructureProcessorHelper STRUCTURE_PROCESSOR_HELPER = new FabricStructureProcessorHelper();
    public static final Platform PLATFORM = new Platform();

    public static final class Platform {
        public String getPlatformName() { return "Paper"; }
        public boolean isDevelopmentEnvironment() { return false; }
        public boolean isModLoaded(String id) { return false; }
    }
}
