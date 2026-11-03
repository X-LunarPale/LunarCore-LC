package com.lunarboreal.core.service;

import com.lunarboreal.api.service.ServiceRegistry;
import com.lunarboreal.core.LunarCorePlugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的服务注册表。 */
public final class SimpleServiceRegistry implements ServiceRegistry {

    public record Registration(Object implementation, Object owner) {}

    private final LunarCorePlugin plugin;
    private final Map<Class<?>, Registration> services = new ConcurrentHashMap<>();

    public SimpleServiceRegistry(LunarCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public <T> void register(Class<T> type, T implementation, Object owner) {
        if (type == null || implementation == null) throw new IllegalArgumentException("type/implementation 不能为空");
        if (!type.isInstance(implementation)) throw new IllegalArgumentException(implementation.getClass() + " 不是 " + type.getName() + " 的实现");
        Registration old = services.put(type, new Registration(implementation, owner));
        if (old != null && old.owner() != owner) {
            plugin.getLogger().warning("服务 " + type.getSimpleName() + " 被 " + LunarCorePlugin.ownerName(owner)
                    + " 覆盖（原提供方 " + LunarCorePlugin.ownerName(old.owner()) + "）");
        }
        plugin.debug("注册服务 " + type.getName() + " ← " + LunarCorePlugin.ownerName(owner));
    }

    @Override
    public <T> void unregister(Class<T> type) {
        services.remove(type);
    }

    @Override
    public void unregisterAll(Object owner) {
        services.entrySet().removeIf(e -> e.getValue().owner() == owner);
    }

    @Override
    public <T> Optional<T> get(Class<T> type) {
        Registration r = services.get(type);
        return r == null ? Optional.empty() : Optional.of(type.cast(r.implementation()));
    }

    /** 类名 → 提供方，供 web/命令展示。 */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        services.forEach((k, v) -> out.put(k.getName(), LunarCorePlugin.ownerName(v.owner())));
        return out;
    }
}
