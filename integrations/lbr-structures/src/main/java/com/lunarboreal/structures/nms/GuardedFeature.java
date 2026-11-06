package com.lunarboreal.structures.nms;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.structure.Structure;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Server-side equivalents of YUNG API's structure-aware decoration exclusions. */
public record GuardedFeature(Feature delegate, String kind) implements Feature {
    public static final MapCodec<GuardedFeature> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(i -> i.group(
            Feature.DIRECT_CODEC.fieldOf("delegate").forGetter(GuardedFeature::delegate),
            com.mojang.serialization.Codec.STRING.fieldOf("kind").forGetter(GuardedFeature::kind)
    ).apply(i, GuardedFeature::new));
    public GuardedFeature {
        if (!java.util.Set.of("basalt", "delta", "magma", "vines").contains(kind)) throw new IllegalArgumentException("Unknown feature exclusion");
    }
    private static final int WRITE_RADIUS = 1;
    private static final TagKey<Structure> NO_BASALT = tag("yungsapi:remove_basalt_columns_feature_in");
    private static final TagKey<Structure> NO_DELTA = tag("yungsapi:remove_delta_feature_in");
    private static final TagKey<Structure> NO_MAGMA = tag("yungsapi:remove_magma_feature_in");
    private static final TagKey<Structure> NO_VINES = tag("yungsapi:remove_vines_feature_in");
    private static TagKey<Structure> tag(String name) { return TagKey.create(Registries.STRUCTURE, Identifier.parse(name)); }
    @Override public MapCodec<GuardedFeature> codec() { return CODEC; }
    public static Feature wrap(Feature feature, String placedId) {
        if (placedId.equals("minecraft:basalt_columns_large") || placedId.equals("minecraft:basalt_columns_small")
                || placedId.equals("minecraft:large_basalt_columns") || placedId.equals("minecraft:small_basalt_columns"))
            return new GuardedFeature(feature, "basalt");
        if (feature instanceof DeltaFeature) return new GuardedFeature(feature, "delta");
        if (feature instanceof UnderwaterMagmaFeature) return new GuardedFeature(feature, "magma");
        if (feature instanceof VinesFeature) return new GuardedFeature(feature, "vines");
        return feature;
    }
    /**
     * The feature step holds references only within its write radius and starts only within the
     * region, so a neighbouring chunk can reference a start the region cannot read, and asking for
     * it crashes the chunk system. Such a reference alone counts as protected: it depends only on
     * saved data, unlike any answer that waits on chunks outside the region.
     * Protection covers the whole start box, as upstream does: delta and magma features write
     * several blocks from their origin, so a piece test lets them eat decks from the gaps between pieces.
     */
    private static boolean protectedAt(WorldGenRegion region, BlockPos pos, TagKey<Structure> tag) {
        if (region.getCenter().getChessboardDistance(pos.getX() >> 4, pos.getZ() >> 4) > WRITE_RADIUS) return false;
        var manager = region.getLevel().structureManager().forWorldGenRegion(region);
        var structures = region.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (var entry : manager.getAllStructuresAt(pos).entrySet()) {
            if (!structures.wrapAsHolder(entry.getKey()).is(tag)) continue;
            for (long packed : entry.getValue()) {
                var start = ChunkPos.unpack(packed);
                if (!region.hasChunk(start.x(), start.z())) return true;
                var found = manager.getStartForStructure(entry.getKey(), region.getChunk(start.x(), start.z(), ChunkStatus.STRUCTURE_STARTS));
                if (found != null && found.isValid() && found.getBoundingBox().isInside(pos)) return true;
            }
        }
        return false;
    }
    @Override public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        if (!(level instanceof WorldGenRegion region)) return delegate.place(level, generator, random, origin);
        if (kind.equals("delta") && protectedAt(region, origin, NO_DELTA)) return false;
        if (kind.equals("magma") && protectedAt(region, origin, NO_MAGMA)) return false;
        if (kind.equals("vines"))
            for (Direction direction : Direction.Plane.HORIZONTAL) if (protectedAt(region, origin.relative(direction), NO_VINES)) return false;
        if (kind.equals("basalt")) {
            // Basalt's native canPlaceAt rejects bedrock. The view substitutes it only
            // at protected coordinates, and blocks writes there; other calls retain
            // the original bounded WorldGenRegion, RNG and native feature algorithm.
            var view = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                if (args != null && args.length > 0 && args[0] instanceof BlockPos pos
                        && (method.getName().equals("getBlockState") || method.getName().equals("setBlock"))
                        && protectedAt(region, pos, NO_BASALT))
                    return method.getName().equals("getBlockState") ? Blocks.BEDROCK.defaultBlockState() : false;
                try { return method.invoke(level, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
            return delegate.place(view, generator, random, origin);
        }
        return delegate.place(level, generator, random, origin);
    }
}
