package com.lunarboreal.api;

/**
 * 兼容旧扩展的全局入口；新扩展使用 {@link LC}。
 * <pre>
 *   LunarBorealAPI api = LC.get();
 *   EconomyService eco = api.services().require(EconomyService.class);
 * </pre>
 */
public final class LBR {
    private static volatile LunarBorealAPI instance;

    private LBR() {}

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
