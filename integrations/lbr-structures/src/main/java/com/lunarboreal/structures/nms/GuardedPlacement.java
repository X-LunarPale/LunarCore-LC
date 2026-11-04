package com.lunarboreal.structures.nms;

import com.lunarboreal.structures.config.GenerationPolicy;
import com.lunarboreal.structures.config.StructureFamily;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.*;
import java.util.*;

/** Datapack entries default to inactive even if the runtime plugin fails to enable. */
public final class GuardedPlacement extends RandomSpreadStructurePlacement {
    private static final Map<ChunkGeneratorStructureState, GenerationPolicy> POLICIES = Collections.synchronizedMap(new WeakHashMap<>());
    public static final MapCodec<GuardedPlacement> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("family").forGetter((GuardedPlacement p) -> p.family.id),
            StructurePlacement.CODEC.fieldOf("delegate").forGetter((GuardedPlacement p) -> p.delegate)
    ).apply(i, GuardedPlacement::new));
    private final StructureFamily family;
    private final RandomSpreadStructurePlacement delegate;

    public GuardedPlacement(String family, StructurePlacement delegate) {
        this(StructureFamily.require(family), requireRandomSpread(delegate));
    }
    private GuardedPlacement(StructureFamily family, RandomSpreadStructurePlacement delegate) {
        super(delegate.locateOffset, delegate.frequencyReductionMethod, delegate.frequency, delegate.salt,
                delegate.exclusionZone, delegate.spacing(), delegate.separation(), delegate.spreadType());
        this.family = family; this.delegate = delegate;
    }
    private static RandomSpreadStructurePlacement requireRandomSpread(StructurePlacement value) {
        if (value instanceof RandomSpreadStructurePlacement spread) return spread;
        throw new IllegalArgumentException("Unsupported upstream placement type");
    }
    public static void bind(ChunkGeneratorStructureState state, GenerationPolicy policy) { POLICIES.put(Objects.requireNonNull(state), Objects.requireNonNull(policy)); }
    @Override public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z, ResourceKey<StructureSet> key) {
        return POLICIES.getOrDefault(state, GenerationPolicy.vanilla()).enabled(family) && delegate.isStructureChunk(state, x, z, key);
    }
    @Override public ChunkPos getPotentialStructureChunk(long seed, int x, int z) { return delegate.getPotentialStructureChunk(seed, x, z); }
    @Override @SuppressWarnings({"unchecked", "rawtypes"})
    public MapCodec<RandomSpreadStructurePlacement> codec() { return (MapCodec) CODEC; }
}
