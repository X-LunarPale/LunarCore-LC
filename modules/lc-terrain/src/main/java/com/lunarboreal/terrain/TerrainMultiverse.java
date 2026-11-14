package com.lunarboreal.terrain;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;

/** 仅通过 Multiverse 公开 API 登记已加载的原生世界。 */
final class TerrainMultiverse {
    private final ClassLoader loader;
    private final Object manager;

    private TerrainMultiverse(Plugin plugin) throws Exception {
        loader = plugin.getClass().getClassLoader();
        Class<?> api = loader.loadClass("org.mvplugins.multiverse.core.MultiverseCoreApi");
        if (!Boolean.TRUE.equals(api.getMethod("isLoaded").invoke(null))) {
            throw new IllegalStateException("Multiverse API 尚未加载");
        }
        manager = call(api.getMethod("get").invoke(null), "getWorldManager");
    }

    static TerrainMultiverse available() throws Exception {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        return plugin == null || !plugin.isEnabled() ? null : new TerrainMultiverse(plugin);
    }

    void register(World world) throws Exception {
        Object existing = find(world.getKey(), world.getName());
        if (existing == null) {
            Class<?> type = loader.loadClass("org.mvplugins.multiverse.core.world.options.ImportWorldOptions");
            Object options = importOptions(type, world.getKey(), world.getName(), world.getEnvironment());
            success(call(manager, "importWorld", options));
            existing = find(world.getKey(), world.getName());
            if (existing == null) throw new IllegalStateException("Multiverse 未保存世界登记");
        } else if (!Boolean.TRUE.equals(call(existing, "isLoaded"))) {
            Class<?> type = loader.loadClass("org.mvplugins.multiverse.core.world.options.LoadWorldOptions");
            Object options = staticCall(type, "world", existing);
            call(options, "doFolderCheck", false);
            success(call(manager, "loadWorld", options));
            existing = find(world.getKey(), world.getName());
        }
        // LC must initialize native generation before MV can access this world after restart.
        success(call(existing, "setAutoLoad", false));
        success(call(manager, "saveWorldsConfig"));
    }

    static Object importOptions(Class<?> type, NamespacedKey key, String name, World.Environment environment) throws Exception {
        Object options;
        try {
            options = staticCall(type, "worldKey", key);
        } catch (NoSuchMethodException ignored) {
            options = staticCall(type, "worldName", name);
        }
        call(options, "environment", environment);
        call(options, "doFolderCheck", false);
        call(options, "useSpawnAdjust", false);
        return options;
    }

    boolean contains(NamespacedKey key, String name) throws Exception {
        return find(key, name) != null;
    }

    void protectDefault(NamespacedKey key, String name) throws Exception {
        Object option = call(manager, "getDefaultWorld");
        Object world = call(option, "getOrNull");
        if (world != null && matches(world, key, name)) throw new IllegalArgumentException("不能删除或卸载 Multiverse 默认世界");
    }

    void unload(World world) throws Exception {
        Object existing = find(world.getKey(), world.getName());
        if (existing == null || !Boolean.TRUE.equals(call(existing, "isLoaded"))) return;
        Object loaded = call(call(existing, "asLoadedWorld"), "get");
        Class<?> type = loader.loadClass("org.mvplugins.multiverse.core.world.options.UnloadWorldOptions");
        Object options = staticCall(type, "world", loaded);
        call(options, "saveBukkitWorld", true);
        call(options, "unloadBukkitWorld", true);
        success(call(manager, "unloadWorld", options));
    }

    void remove(NamespacedKey key, String name) throws Exception {
        Object existing = find(key, name);
        if (existing == null) return;
        protectDefault(key, name);
        Class<?> type = loader.loadClass("org.mvplugins.multiverse.core.world.options.RemoveWorldOptions");
        Object options = staticCall(type, "world", existing);
        call(options, "saveBukkitWorld", true);
        call(options, "unloadBukkitWorld", true);
        success(call(manager, "removeWorld", options));
        success(call(manager, "saveWorldsConfig"));
    }

    private Object find(NamespacedKey key, String name) throws Exception {
        Object direct = call(call(manager, "getWorld", key.toString()), "getOrNull");
        if (direct != null) return direct;
        for (Object world : (Collection<?>) call(manager, "getWorlds")) {
            if (matches(world, key, name)) return world;
        }
        return null;
    }

    private static boolean matches(Object world, NamespacedKey key, String name) throws Exception {
        try {
            return key.equals(call(world, "getKey"));
        } catch (NoSuchMethodException ignored) {
            return name.equals(call(world, "getName")) || key.toString().equals(call(world, "getName"));
        }
    }

    private static Object staticCall(Class<?> type, String method, Object... args) throws Exception {
        return invoke(type, null, method, args);
    }

    private static Object call(Object target, String method, Object... args) throws Exception {
        return invoke(target.getClass(), target, method, args);
    }

    private static Object invoke(Class<?> type, Object target, String name, Object... args) throws Exception {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length
                    || target == null && !Modifier.isStatic(method.getModifiers())) continue;
            boolean matches = true;
            for (int index = 0; index < args.length; index++) {
                Class<?> parameter = method.getParameterTypes()[index];
                if (parameter == boolean.class) parameter = Boolean.class;
                if (args[index] != null && !parameter.isInstance(args[index])) matches = false;
            }
            if (!matches) continue;
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw failure;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static void success(Object result) throws Exception {
        if (!Boolean.TRUE.equals(call(result, "isSuccess"))) throw new IllegalStateException("Multiverse 操作失败：" + result);
    }
}
