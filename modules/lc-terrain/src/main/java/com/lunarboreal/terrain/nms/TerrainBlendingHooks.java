package com.lunarboreal.terrain.nms;

import com.lunarboreal.terrain.TerrainNamespace;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/** Chunk-generation version markers used by the native old-chunk blender. */
public final class TerrainBlendingHooks {
    static final String VERSION_KEY = "tectonic:blending_version";
    static final int VERSION = 1;
    private static final Set<String> UNGENERATED = Set.of("minecraft:empty", "minecraft:structure_starts",
            "minecraft:structure_references", "minecraft:biomes");
    private static final TagKey<Block> TERRALITH_SURFACE = TagKey.create(Registries.BLOCK,
            Identifier.parse("tectonic:terralith_surface"));
    private static Field heightAccessor;
    private static boolean installed;

    private TerrainBlendingHooks() {}

    public static void install() throws Exception {
        try {
            NativeTectonicClimate.hook("requireInstalledHooks", new Class<?>[]{ClassLoader.class},
                    ChunkAccess.class.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("请将 LC-Terrain-Agent.jar 放入服务端目录，并添加启动参数 "
                    + "-javaagent:LC-Terrain-Agent.jar 后完整重启服务器", failure);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new IllegalStateException("原生地形启动组件未生效，请使用当前 LC-Terrain-Agent.jar 并添加 "
                    + "-javaagent:LC-Terrain-Agent.jar 后完整重启服务器：" + failure.getCause().getMessage(),
                    failure.getCause());
        }
        heightAccessor = ChunkAccess.class.getDeclaredField("levelHeightAccessor");
        heightAccessor.setAccessible(true);
        NativeTectonicClimate.hook("registerBlendingHooks",
                new Class<?>[]{BiConsumer.class, Function.class, BiFunction.class},
                (BiConsumer<Object, Object>) TerrainBlendingHooks::beforeParse,
                (Function<Object[], Object>) TerrainBlendingHooks::isOldChunk,
                (BiFunction<Object, Object, Object>) TerrainBlendingHooks::afterWrite);
        NativeTectonicClimate.hook("registerChunkCompatibilityHooks",
                new Class<?>[]{BiPredicate.class, Predicate.class},
                (BiPredicate<Object, Object>) TerrainBlendingHooks::isSurfaceBlock,
                (Predicate<Object>) TerrainBlendingHooks::suppressHeightmapWarning);
        installed = true;
    }

    public static void uninstall() throws Exception {
        if (!installed) return;
        NativeTectonicClimate.hook("registerBlendingHooks",
                new Class<?>[]{BiConsumer.class, Function.class, BiFunction.class}, null, null, null);
        NativeTectonicClimate.hook("registerChunkCompatibilityHooks",
                new Class<?>[]{BiPredicate.class, Predicate.class}, null, null);
        heightAccessor = null;
        installed = false;
    }

    static boolean isSurfaceBlock(Object chunk, Object block) {
        return chunk instanceof ChunkAccess access && managed(access)
                && block instanceof Block value && value.defaultBlockState().is(TERRALITH_SURFACE);
    }

    static boolean suppressHeightmapWarning(Object chunk) {
        return chunk instanceof ChunkAccess access && managed(access);
    }

    private static boolean managed(ChunkAccess chunk) {
        Field field = heightAccessor;
        if (field == null) return false;
        try {
            return field.get(chunk) instanceof Level level && managed(level.dimension());
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read the terrain chunk's world", failure);
        }
    }

    public static void beforeParse(Object world, Object tag) {
        if (world instanceof Level level && tag instanceof CompoundTag data) {
            prepare(level.dimension(), data);
        }
    }

    public static Object isOldChunk(Object[] arguments) {
        RegionStorageInfo storage = (RegionStorageInfo) arguments[0];
        CompoundTag tag = (CompoundTag) arguments[1];
        return (Boolean) arguments[2] || managed(storage.dimension()) && outdated(tag);
    }

    public static Object afterWrite(Object world, Object tag) {
        if (world instanceof Level level && tag instanceof CompoundTag data) {
            stamp(level.dimension(), data);
        }
        return tag;
    }

    static void prepare(ResourceKey<Level> dimension, CompoundTag tag) {
        if (!managed(dimension) || !outdated(tag) || UNGENERATED.contains(tag.getStringOr("Status", ""))) return;
        int minimum = -4;
        int maximum = 20;
        for (var entry : tag.getListOrEmpty("sections")) {
            if (entry instanceof CompoundTag section && section.contains("block_states")) {
                int y = section.getIntOr("Y", 0);
                minimum = Math.min(minimum, y);
                maximum = Math.max(maximum, y + 1);
            }
        }
        CompoundTag blending = new CompoundTag();
        blending.putInt("min_section", minimum);
        blending.putInt("max_section", maximum);
        tag.put("blending_data", blending);
        tag.remove("Heightmaps");
        tag.remove("isLightOn");
    }

    static void stamp(ResourceKey<Level> dimension, CompoundTag tag) {
        if (managed(dimension)) tag.putInt(VERSION_KEY, VERSION);
    }

    private static boolean outdated(CompoundTag tag) {
        return tag.getIntOr(VERSION_KEY, 0) != VERSION;
    }

    private static boolean managed(ResourceKey<Level> dimension) {
        return dimension != null && TerrainNamespace.isTerrain(dimension.identifier().getNamespace());
    }
}
