package com.lunarboreal.integration.multiverse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

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
        return world(manager,name);
    }
    static Object world(Object manager,String name) throws Exception {
        Object direct=call(call(manager,"getWorld",name),"getOrNull");
        if(direct!=null&&name.equals(identity(direct)))return direct;
        List<Object> named=new ArrayList<>(),aliased=new ArrayList<>();
        for(Object w : worlds(manager)){
            if(name.equals(identity(w)))return w;
            if(name.equalsIgnoreCase(call(w,"getName").toString()))named.add(w);
            try{if(name.equalsIgnoreCase(Objects.toString(call(w,"getAlias"),"")))aliased.add(w);}
            catch(NoSuchMethodException ignored){}
        }
        List<Object> matches=named.isEmpty()?aliased:named;
        if(matches.size()>1)throw new IllegalArgumentException("多个世界使用此名称或别名，请使用完整维度标识");
        if(matches.size()==1)return matches.getFirst();
        if(direct!=null)return direct;
        throw new IllegalArgumentException("世界未在Multiverse注册");
    }
    static Collection<Object> worlds(Object manager)throws Exception{
        Map<String,Object> worlds=new LinkedHashMap<>();
        // MV 5.8.1 omits newly unloaded worlds from getWorlds().
        for(String getter:List.of("getWorlds","getUnloadedWorlds","getLoadedWorlds")){
            Collection<?> values;
            try{values=(Collection<?>)call(manager,getter);}
            catch(NoSuchMethodException ignored){if(getter.equals("getWorlds"))throw ignored;continue;}
            for(Object world:values)worlds.put(identity(world),world);
        }
        return worlds.values();
    }
    static String identity(Object world) throws Exception {
        try { return call(world,"getKey").toString(); }
        catch (NoSuchMethodException ignored) { return call(world,"getName").toString(); }
    }
    static World bukkitWorld(Object world) throws Exception {
        try {
            Object key=call(world,"getKey");
            if(key instanceof NamespacedKey namespacedKey)return Bukkit.getWorld(namespacedKey);
        } catch(NoSuchMethodException ignored) {}
        return Bukkit.getWorld(call(world,"getName").toString());
    }
}
