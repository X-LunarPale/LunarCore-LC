package com.lunarboreal.api;

/** LunarCore 全局入口。 */
public final class LC {
    private LC() {}

    public static LunarBorealAPI get() {
        return LBR.get();
    }

    public static boolean isLoaded() {
        return LBR.isLoaded();
    }
}
