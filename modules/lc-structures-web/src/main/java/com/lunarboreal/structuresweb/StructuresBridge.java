package com.lunarboreal.structuresweb;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/** Calls only the generation plugin's public, versioned API. No shared vendor classes. */
public final class StructuresBridge {
    private record Binding(Object api, Method status, Method read, Method save) {}
    private Binding binding;
    private String problem = "结构生成插件未安装或未启用";

    public synchronized void bind(Object plugin) {
        disconnect();
        if (plugin == null) return;
        try {
            // The runtime class belongs to the plugin's ClassLoader, not the LC module loader.
            Object api = plugin.getClass().getMethod("getManagementApi").invoke(plugin);
            Class<?> type = api.getClass();
            if (!Integer.valueOf(1).equals(type.getMethod("apiVersion").invoke(api)))
                throw new IllegalArgumentException("Unsupported management API version");
            Method status = type.getMethod("status"), read = type.getMethod("readConfig");
            Method save = type.getMethod("saveConfig", Map.class);
            for (Method method : new Method[]{status, read, save})
                if (!Map.class.isAssignableFrom(method.getReturnType()))
                    throw new IllegalArgumentException("Invalid management API signature");
            binding = new Binding(api, status, read, save);
            problem = "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            problem = "结构生成插件管理接口不兼容，请更新插件与桥接模块";
        }
    }

    public synchronized void disconnect() {
        binding = null;
        problem = "结构生成插件未安装或未启用";
    }

    public synchronized boolean connected() { return binding != null; }
    public synchronized String problem() { return problem; }
    public synchronized Object connection() { return binding; }
    public synchronized boolean isCurrent(Object token) { return token != null && binding == token; }

    /** Must be invoked on the server thread. */
    public synchronized Map<String, Object> status() throws IOException {
        Binding b = require();
        return invoke(b, b.status());
    }

    public synchronized Map<String, Object> readConfig() throws IOException {
        Binding b = require();
        return invoke(b, b.read());
    }

    public synchronized Map<String, Object> saveConfig(Map<String, Object> requested) throws IOException {
        Binding b = require();
        return invoke(b, b.save(), requested);
    }

    private Binding require() {
        if (binding == null) throw new IllegalStateException(problem);
        return binding;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(Binding b, Method method, Object... args) throws IOException {
        try {
            Object result = method.invoke(b.api(), args);
            if (!(result instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String)))
                throw new IllegalStateException("结构生成插件返回了无效数据");
            return (Map<String, Object>) result;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            if (e.getCause() instanceof IOException io) throw io;
            throw new IllegalStateException("结构生成插件操作失败", e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            disconnect();
            throw new IllegalStateException("结构生成插件管理接口不兼容，请更新插件与桥接模块", e);
        }
    }
}
