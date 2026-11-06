package com.lunarboreal.terrain.agent;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/** The agent bridge uses JDK types so the Paper and module class loaders share one policy. */
public final class NativeTerrainHooks {
    private record Policy(int monumentOffset, int snowOffset) {}
    private static final Policy VANILLA = new Policy(0, 0);
    private static final Map<Object, Policy> WORLDS = new ConcurrentHashMap<>();
    private static final Map<Object, Policy> GENERATORS = new ConcurrentHashMap<>();
    private static final Map<Object, Integer> BIOMES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Function<Object, Object>> BIOME_MANAGERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Object> BIOME_ALIASES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Policy> CURRENT = new ThreadLocal<>();
    private static final ReferenceQueue<Object> CHUNK_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityReference, Object> CHUNK_WORLDS = new ConcurrentHashMap<>();
    private static final Set<String> TRANSFORMED = ConcurrentHashMap.newKeySet();
    private static volatile boolean installed;
    private static volatile Object biomeRegistry;
    private static volatile BiConsumer<Object, Object> beforeParse;
    private static volatile Function<Object[], Object> oldChunk;
    private static volatile BiFunction<Object, Object, Object> afterWrite;
    private static volatile BiPredicate<Object, Object> surfaceBlock;
    private static volatile Predicate<Object> heightmapWarning;
    private static final ClassValue<Method> BELOW = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            return method(type, "below", int.class);
        }
    };
    private static final ClassValue<Method> MOVED = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            return method(type, "moved", int.class, int.class, int.class);
        }
    };
    private static final ClassValue<Method> HOLDER_KEY = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            try {
                return Class.forName("net.minecraft.core.Holder", false, type.getClassLoader())
                        .getMethod("unwrapKey");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native biome holder API changed", failure);
            }
        }
    };
    private static final ClassValue<Method> KEY_REGISTRY = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) { return method(type, "registry"); }
    };

    private NativeTerrainHooks() {}

    public static void installed() { installed = true; }
    public static void transformed(String name) { TRANSFORMED.add(name); }

    public static boolean isInstalled() { return installed; }
    public static boolean hasTransformation(String name) { return TRANSFORMED.contains(name); }

    public static void requireInstalledHooks(ClassLoader loader) {
        if (!installed) throw new IllegalStateException("Start Paper with -javaagent:LC-Terrain-Agent.jar");
        try {
            Class<?> agent = Class.forName("com.lunarboreal.terrain.agent.NativeTerrainAgent", true,
                    ClassLoader.getSystemClassLoader());
            agent.getMethod("requireTransformations", ClassLoader.class).invoke(null, loader);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Cannot check the native terrain hooks", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot reach the native terrain agent", failure);
        }
    }

    public static void registerWorld(Object level, Object generator, int monumentOffset, int snowOffset) {
        Policy policy = new Policy(monumentOffset, snowOffset);
        WORLDS.put(level, policy);
        GENERATORS.put(generator, policy);
    }

    public static void unregisterWorld(Object level, Object generator) {
        WORLDS.remove(level);
        GENERATORS.remove(generator);
    }

    public static void registerBiome(Object biome, int offset) {
        if (offset == 0) BIOMES.remove(biome);
        else BIOMES.put(biome, offset);
    }

    public static void unregisterBiome(Object biome) { BIOMES.remove(biome); }

    public static void registerBiomeManager(Object manager, Function<Object, Object> resolver) {
        BIOME_MANAGERS.put(manager, resolver);
    }

    public static void unregisterBiomeManager(Object manager) { BIOME_MANAGERS.remove(manager); }

    public static void registerBiomeAlias(Object aliasKey, Object vanillaKey) {
        biomeRegistry = invoke(KEY_REGISTRY.get(aliasKey.getClass()), aliasKey);
        BIOME_ALIASES.put(aliasKey, vanillaKey);
    }

    public static boolean matchesBiomeAlias(Object holder, Object query, boolean original) {
        if (original || BIOME_ALIASES.isEmpty()) return original;
        Object key = invoke(method(holder.getClass(), "key"), holder);
        return query.equals(BIOME_ALIASES.get(key));
    }

    public static boolean matchesBiomeSet(Object holders, Object holder, boolean original) {
        if (original || BIOME_ALIASES.isEmpty()) return original;
        Optional<?> key = (Optional<?>) invoke(HOLDER_KEY.get(holder.getClass()), holder);
        if (key.isEmpty()) return false;
        Object candidate = key.get(), alias = BIOME_ALIASES.get(candidate);
        if (!biomeRegistry.equals(invoke(KEY_REGISTRY.get(candidate.getClass()), candidate))) return false;
        for (Object member : (Iterable<?>) holders) {
            Object memberKey = ((Optional<?>) invoke(HOLDER_KEY.get(member.getClass()), member)).orElse(null);
            if (candidate.equals(memberKey) || alias != null && alias.equals(memberKey)) return true;
        }
        return false;
    }

    public static Object resolveBiome(Object manager, Object holder) {
        Function<Object, Object> resolver = BIOME_MANAGERS.get(manager);
        return resolver == null ? holder : resolver.apply(holder);
    }

    public static void inheritBiomeManager(Object region) {
        Object level = invoke(method(region.getClass(), "getLevel"), region);
        Object source = invoke(method(level.getClass(), "getBiomeManager"), level);
        Function<Object, Object> resolver = BIOME_MANAGERS.get(source);
        if (resolver != null) {
            Object target = invoke(method(region.getClass(), "getBiomeManager"), region);
            BIOME_MANAGERS.put(target, resolver);
        }
    }

    public static Object enterGenerator(Object generator) {
        Policy previous = CURRENT.get();
        CURRENT.set(GENERATORS.getOrDefault(generator, VANILLA));
        return previous;
    }

    public static Object enterWorld(Object level) {
        Policy previous = CURRENT.get();
        CURRENT.set(WORLDS.getOrDefault(level, VANILLA));
        return previous;
    }

    public static void leave(Object previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set((Policy) previous);
    }

    public static Object monumentBounds(Object box) {
        Policy policy = CURRENT.get();
        if (policy == null || policy.monumentOffset() == 0) return box;
        return invoke(MOVED.get(box.getClass()), box, 0, policy.monumentOffset(), 0);
    }

    public static Object temperaturePosition(Object biome, Object position) {
        int offset = BIOMES.getOrDefault(biome, 0);
        return offset == 0 ? position : invoke(BELOW.get(position.getClass()), position, offset);
    }

    public static Object adjustSpawnInfo(Object level, Object info) {
        Policy policy = WORLDS.get(level);
        if (policy == null || policy.snowOffset() == 0) return info;
        RecordComponent[] components = info.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        boolean seaLevel = false;
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = invoke(components[i].getAccessor(), info);
            if (components[i].getName().equals("seaLevel")) {
                values[i] = ((Integer) values[i]) + policy.snowOffset();
                seaLevel = true;
            }
        }
        if (!seaLevel) throw new IllegalStateException("CommonPlayerSpawnInfo no longer contains seaLevel");
        try {
            Constructor<?> constructor = info.getClass().getDeclaredConstructor(types);
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot adapt the terrain client's snowline", failure);
        }
    }

    public static void registerBlendingHooks(BiConsumer<Object, Object> parse,
            Function<Object[], Object> old, BiFunction<Object, Object, Object> write) {
        beforeParse = parse;
        oldChunk = old;
        afterWrite = write;
        if (write == null) {
            CHUNK_WORLDS.clear();
            drainChunks();
        }
    }

    public static void registerChunkCompatibilityHooks(BiPredicate<Object, Object> surface,
            Predicate<Object> warning) {
        surfaceBlock = surface;
        heightmapWarning = warning;
    }

    public static boolean isSurfaceBlock(Object blocks, Object block, Object chunk) {
        if (((java.util.List<?>) blocks).contains(block)) return true;
        BiPredicate<Object, Object> hook = surfaceBlock;
        return hook != null && hook.test(chunk, block);
    }

    public static boolean suppressHeightmapWarning(Object chunk) {
        Predicate<Object> hook = heightmapWarning;
        return hook != null && hook.test(chunk);
    }

    public static boolean outsideSections(Object[] positions, int index) {
        return index < 0 || index >= positions.length;
    }

    public static void beforeParse(Object heightAccessor, Object tag) {
        BiConsumer<Object, Object> hook = beforeParse;
        if (hook != null) hook.accept(heightAccessor, tag);
    }

    public static boolean isOldChunk(Object storage, Object tag, boolean original) {
        Function<Object[], Object> hook = oldChunk;
        return hook == null ? original : (Boolean) hook.apply(new Object[] {storage, tag, original});
    }

    public static void afterCopy(Object level, Object data) {
        if (data == null || afterWrite == null) return;
        drainChunks();
        CHUNK_WORLDS.put(new IdentityReference(data, CHUNK_QUEUE), level);
    }

    public static Object afterWrite(Object data, Object tag) {
        BiFunction<Object, Object, Object> hook = afterWrite;
        if (hook == null) return tag;
        drainChunks();
        Object level = CHUNK_WORLDS.get(new IdentityReference(data, null));
        return level == null ? tag : hook.apply(level, tag);
    }

    private static void drainChunks() {
        IdentityReference reference;
        while ((reference = (IdentityReference) CHUNK_QUEUE.poll()) != null) CHUNK_WORLDS.remove(reference);
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) {
        try {
            return type.getMethod(name, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native terrain API changed: " + type.getName() + "." + name, failure);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native terrain hook failed: " + method, failure);
        }
    }

    private static final class IdentityReference extends WeakReference<Object> {
        private final int hash;

        IdentityReference(Object value, ReferenceQueue<Object> queue) {
            super(value, queue);
            hash = System.identityHashCode(value);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof IdentityReference reference && get() != null && get() == reference.get();
        }
    }
}
