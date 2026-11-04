package com.lunarboreal.structures.nms;

import com.lunarboreal.api.service.NativeGeneratorDelegate;
import com.lunarboreal.structures.config.GenerationPolicy;
import com.lunarboreal.structures.config.StructureFamily;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.structure.Structure;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Runtime overlay; the world's saved LevelStem continues to contain its original noise generator. */
public class StructureChunkGenerator extends ChunkGenerator implements NativeGeneratorDelegate {
    private final NoiseBasedChunkGenerator delegate;
    private final GenerationPolicy policy;
    public StructureChunkGenerator(NoiseBasedChunkGenerator delegate, GenerationPolicy policy) {
        super(delegate.getBiomeSource(), delegate.generationSettingsGetter);
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override public Object underlyingGenerator() { return delegate; }
    @Override public Optional<net.minecraft.resources.Identifier> getTypeNameForDataFixer() { return delegate.getTypeNameForDataFixer(); }
    @Override protected MapCodec<? extends ChunkGenerator> codec() {
        throw new IllegalStateException("Runtime structure overlay must not replace the saved LevelStem");
    }
    @Override public ChunkPos getOrigin(RandomState random) { return delegate.getOrigin(random); }
    @Override public int getGenDepth() { return delegate.getGenDepth(); }
    @Override public int getSeaLevel() { return delegate.getSeaLevel(); }
    @Override public int getMinY() { return delegate.getMinY(); }
    @Override public void spawnOriginalMobs(WorldGenRegion region) { delegate.spawnOriginalMobs(region); }
    @Override public net.minecraft.util.random.WeightedList<MobSpawnSettings.SpawnerData> getMobsAt(Level level, StructureManager structures,
            net.minecraft.world.entity.MobCategory category, BlockPos position) {
        if (category == net.minecraft.world.entity.MobCategory.MONSTER && level.getBlockState(position.below()).is(net.minecraft.world.level.block.Blocks.NETHER_BRICKS)) {
            var fortress = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .getValue(net.minecraft.resources.Identifier.parse("betterfortresses:fortress"));
            if (fortress != null && structures.getStructureAt(position, fortress).isValid())
                return net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure.FORTRESS_ENEMIES;
        }
        return super.getMobsAt(level, structures, category, position);
    }
    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState random) {
        return delegate.getBaseHeight(x, z, type, height, random);
    }
    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        return delegate.getBaseColumn(x, z, height, random);
    }
    @Override public void addDebugScreenInfo(List<String> lines, RandomState random, BlockPos pos, SamplerContext context) {
        delegate.addDebugScreenInfo(lines, random, pos, context);
    }
    @Override public CompletableFuture<ChunkAccess> createBiomes(RandomState random, Blender blender, StructureManager manager, ChunkAccess chunk) {
        return delegate.createBiomes(random, blender, manager, chunk);
    }
    @Override public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> requested,
            BlockPos origin, int radius, boolean unexplored) {
        var keys = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var targets = new LinkedHashSet<Holder<Structure>>();
        for (var holder : requested) {
            String id = holder.unwrapKey().orElseThrow().identifier().toString();
            var family = Arrays.stream(StructureFamily.values()).filter(f -> f.vanillaStructures.contains(id) && policy.enabled(f)).findFirst();
            if (family.isPresent()) keys.listElements().filter(h -> h.key().identifier().getNamespace().equals(family.get().namespace)).forEach(targets::add);
            else targets.add(holder);
        }
        return super.findNearestMapStructure(level, HolderSet.direct(List.copyOf(targets)), origin, radius, unexplored);
    }

    @Override public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState random,
            StructureManager manager, BiomeManager biomes, WorldGenRegion region, Set<Holder<Biome>> possibleBiomes) {
        var density = new EnhancedStructureDensity(manager, chunk.getPos());
        if (!density.hasAdaptations()) return delegate.buildTerrain(chunk, blender, random, manager, biomes, region, possibleBiomes);
        try {
            var settings = delegate.generatorSettings().value();
            var noiseSettings = settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
            if (noiseSettings.height() <= 0 || net.minecraft.SharedConstants.debugVoidTerrain(chunk.getPos()))
                return CompletableFuture.completedFuture(chunk);
            var volume = new DensityVolume(16, noiseSettings.height(), 16, chunk.getPos().getMinBlockX(), noiseSettings.minY(), chunk.getPos().getMinBlockZ());
            java.util.function.Supplier<Aquifer.FluidPicker> fluid = PaperAccess.read(delegate, "globalFluidPicker");
            try (var noise = new NoiseChunk(random, density, settings, fluid.get(), blender, volume)) {
                var aquifer = noise.aquifer();
                PaperAccess.write(noise, "aquifer", new Aquifer() {
                    @Override public net.minecraft.world.level.block.state.BlockState computeSubstance(int x, int y, int z, double value) {
                        return density.fluid(x, y, z, aquifer.computeSubstance(x, y, z, value), settings.defaultBlock());
                    }
                    @Override public boolean shouldScheduleFluidUpdate() { return aquifer.shouldScheduleFluidUpdate(); }
                });
                var locked = new ArrayList<LevelChunkSection>();
                try {
                    for (int index = chunk.getSectionIndex(volume.maxBlockY()); index >= chunk.getSectionIndex(volume.minBlockY()); index--) {
                        var section = chunk.getSection(index);
                        section.acquire();
                        locked.add(section);
                    }
                    PaperAccess.invoke(delegate, "doFill", new Class<?>[]{NoiseChunk.class, ChunkAccess.class}, noise, chunk);
                } finally { for (var section : locked) section.release(); }
                var material = settings.materialRule().value();
                var ruleType = net.minecraft.world.level.levelgen.material.rule.MaterialRule.class;
                PaperAccess.invoke(delegate, "buildSurface", new Class<?>[]{ChunkAccess.class, NoiseChunk.class, RandomState.class,
                        BiomeManager.class, Set.class, ruleType, ServerLevel.class}, chunk, noise, random, biomes, possibleBiomes, material, manager.level.getMinecraftWorld());
                PaperAccess.invoke(delegate, "generateCarvers", new Class<?>[]{ChunkAccess.class, Blender.class, NoiseChunk.class,
                        RandomState.class, BiomeManager.class, WorldGenRegion.class, ruleType}, chunk, blender, noise, random, biomes, region, material);
            }
            return CompletableFuture.completedFuture(chunk);
        } catch (ReflectiveOperationException failure) { return CompletableFuture.failedFuture(failure); }
    }
}
