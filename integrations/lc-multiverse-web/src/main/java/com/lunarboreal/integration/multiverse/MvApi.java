package com.lunarboreal.integration.multiverse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.plugin.Plugin;

/** Calls public methods only; no vendor implementation or dependency is bundled. */
final class MvApi {
    final Object api, manager;
    final ClassLoader loader;
    MvApi(Plugin plugin) throws Exception {
        loader = plugin.getClass().getClassLoader();
        Class<?> type = loader.loadClass("org.mvplugins.multiverse.core.MultiverseCoreApi");
        if (!Boolean.TRUE.equals(type.getMethod("isLoaded").invoke(null))) throw new IllegalStateException("Multiverse API尚未加载");
        api = type.getMethod("get").invoke(null);
        manager = call(api, "getWorldManager");
    }
    static Object call(Object target, String name, Object... args) throws Exception {
        Method match = null;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            boolean fits = true;
            for (int i=0; i<args.length; i++) if (args[i] != null && !boxed(m.getParameterTypes()[i]).isInstance(args[i])) fits=false;
            if (fits) { match=m; break; }
        }
        if (match == null) throw new NoSuchMethodException(target.getClass().getName()+"."+name);
        try { return match.invoke(target,args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }
    private static Class<?> boxed(Class<?> type) {
        if(type==boolean.class)return Boolean.class;
        if(type==int.class)return Integer.class;
        return type;
    }
    static void success(Object result) throws Exception {
        if (!Boolean.TRUE.equals(call(result,"isSuccess"))) throw new IllegalStateException("Multiverse拒绝操作: "+result);
    }
    Object world(String name) throws Exception {
        for(Object w : (Collection<?>)call(manager,"getWorlds")) if(name.equals(call(w,"getName"))) return w;
        throw new IllegalArgumentException("世界未在Multiverse注册");
    }
}
