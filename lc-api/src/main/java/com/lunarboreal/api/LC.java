package com.lunarboreal.api;

/** LunarCore 全局入口。 */
public final class LC {
    private static volatile LunarBorealAPI instance;

    private LC() {}

    public static LunarBorealAPI get() {
        LunarBorealAPI api = instance;
        if (api == null) {
            throw new IllegalStateException("LunarCore 尚未加载");
        }
        return api;
    }

    public static boolean isLoaded() {
        return instance != null;
    }

    /** 仅供 core 调用。 */
    public static void setInstance(LunarBorealAPI api) {
        instance = api;
    }
}
