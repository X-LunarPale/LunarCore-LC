package com.lunarboreal.api.world;

/** 主线程上的原生地形世界加载入口。 */
public interface TerrainWorldLifecycle {
    boolean manages(String keyOrName);

    void load(String keyOrName, String actor) throws Exception;

    void unload(String keyOrName, String actor) throws Exception;

    boolean autoLoad(String keyOrName);

    void autoLoad(String keyOrName, boolean enabled, String actor) throws Exception;
}
