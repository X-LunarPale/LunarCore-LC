package com.lunarboreal.structures.nms;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;

/** Checked accessor replacement; does not transform or instrument server bytecode. */
public final class PaperAccess {
    private PaperAccess() {}
    private record FieldKey(Class<?> owner, String name) {}
    private static final ConcurrentHashMap<FieldKey, Field> FIELDS = new ConcurrentHashMap<>();

    public static Field field(Class<?> owner, String name) {
        return FIELDS.computeIfAbsent(new FieldKey(owner, name), key -> {
            for (Class<?> cls = key.owner; cls != null; cls = cls.getSuperclass()) {
                try {
                    var field = cls.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) { }
            }
            throw new IllegalStateException("Incompatible Paper native field: " + owner.getName() + "." + name);
        });
    }

    @SuppressWarnings("unchecked")
    public static <T> T read(Object target, String name) {
        try { return (T) field(target.getClass(), name).get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    public static void write(Object target, String name, Object value) {
        try { field(target.getClass(), name).set(target, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    public static <T> T view(Object target, Class<T> accessor) {
        return accessor.cast(Proxy.newProxyInstance(accessor.getClassLoader(), new Class<?>[]{accessor}, (proxy, method, args) -> {
            String name = method.getName();
            var mappedMethod = method.getAnnotation(NativeMethod.class);
            if (mappedMethod != null) return invoke(target, mappedMethod.value(), method.getParameterTypes(), args);
            if (name.startsWith("call")) return invoke(target, decap(name.substring(4)), method.getParameterTypes(), args);
            if (name.startsWith("invoke")) return invoke(target, decap(name.substring(6)), method.getParameterTypes(), args);
            var mappedField = method.getAnnotation(NativeField.class);
            String field = mappedField == null ? decap(name.substring(3)) : mappedField.value();
            if (name.startsWith("get")) return read(target, field);
            if (name.startsWith("set")) { write(target, field, args[0]); return null; }
            throw new IllegalStateException("Unsupported accessor: " + method);
        }));
    }

    private static String decap(String name) { return Character.toLowerCase(name.charAt(0)) + name.substring(1); }

    public static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Method method = cls.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, args);
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }
}
