package com.lunarboreal.api.service;

import java.util.Optional;

/**
 * 模块间服务注册表。提供方在 onEnable 注册，模块禁用时 core 自动注销其注册的服务。
 * 消费方请每次使用时 get，不要长期缓存实例（提供方可能被热重载）。
 */
public interface ServiceRegistry {

    /** owner 为 LcModule 或 org.bukkit.plugin.Plugin。 */
    <T> void register(Class<T> type, T implementation, Object owner);

    <T> void unregister(Class<T> type);

    void unregisterAll(Object owner);

    <T> Optional<T> get(Class<T> type);

    default <T> T require(Class<T> type) {
        return get(type).orElseThrow(() -> new IllegalStateException("服务不可用: " + type.getSimpleName()));
    }
}
