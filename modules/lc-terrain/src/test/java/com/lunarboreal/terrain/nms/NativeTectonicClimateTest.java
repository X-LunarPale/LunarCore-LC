package com.lunarboreal.terrain.nms;

import com.lunarboreal.terrain.TerrainOptions;
import com.lunarboreal.terrain.TerrainNamespace;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NativeTectonicClimateTest {
    private static HolderLookup.Provider lookup;
    private static MappedRegistry<Biome> biomes;

    public record SpawnInfo(int seaLevel) {}
    public record Box(int minY) {
        public Box moved(int x, int y, int z) { return new Box(minY + y); }
    }
    public record ManagedWorld(BiomeManager manager) {
        public BiomeManager getBiomeManager() { return manager; }
    }
    public record Region(ManagedWorld level, BiomeManager manager) {
        public ManagedWorld getLevel() { return level; }
        public BiomeManager getBiomeManager() { return manager; }
    }

    @BeforeAll static void loadRegistries() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var vanilla = VanillaRegistries.createWorldLookup();
        biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        vanilla.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
                biomes.register(holder.key(), holder.value(),
                        new RegistrationInfo(Optional.empty(), Lifecycle.stable()))
                        .bindComponents(DataComponentMap.EMPTY));
        biomes.freeze();
        biomes.prepareTagReload(new TagLoader.LoadResult<>(Registries.BIOME, Map.of(
                BiomeTags.IS_OCEAN, List.of(biomes.getOrThrow(Biomes.FROZEN_OCEAN)),
                BiomeTags.IS_OVERWORLD, List.of(biomes.getOrThrow(Biomes.FROZEN_OCEAN),
                        biomes.getOrThrow(Biomes.PLAINS)),
                BiomeTags.REQUIRED_OCEAN_MONUMENT_SURROUNDING,
                        List.of(biomes.getOrThrow(Biomes.FROZEN_OCEAN))))).apply();
        var oceanTag = biomes.getOrThrow(BiomeTags.IS_OCEAN);
        var surroundingTag = biomes.getOrThrow(BiomeTags.REQUIRED_OCEAN_MONUMENT_SURROUNDING);
        NativeTectonicClimate.initialize(biomes);
        assertSame(oceanTag, biomes.getOrThrow(BiomeTags.IS_OCEAN),
                "Adding a biome must preserve previously resolved named tags");
        assertSame(surroundingTag, biomes.getOrThrow(BiomeTags.REQUIRED_OCEAN_MONUMENT_SURROUNDING));
        assertEquals(3, oceanTag.size());
        assertEquals(3, surroundingTag.size());

        var parameters = new MappedRegistry<>(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, Lifecycle.stable());
        parameters.register(MultiNoiseBiomeSourceParameterLists.OVERWORLD,
                new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, biomes),
                new RegistrationInfo(Optional.empty(), Lifecycle.stable())).bindComponents(DataComponentMap.EMPTY);
        parameters.freeze();
        lookup = HolderLookup.Provider.create(Stream.concat(
                vanilla.listRegistries().filter(registry -> !registry.key().equals(Registries.BIOME)
                        && !registry.key().equals(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)),
                Stream.of(biomes, parameters)));
    }

    @AfterEach void clearWorlds() throws Exception {
        NativeTectonicClimate.close();
    }

    @Test void frozenOceanVariantPreservesTheOriginalClimateTagsAndIdentity() throws Exception {
        Holder.Reference<Biome> original = biomes.getOrThrow(Biomes.FROZEN_OCEAN);
        Holder.Reference<Biome> replacement = biomes.getOrThrow(NativeTectonicClimate.ICE_FREE_FROZEN_OCEAN);
        assertEquals(0.0F, original.value().getBaseTemperature());
        assertEquals(0.5F, replacement.value().getBaseTemperature());
        assertEquals(Biome.TemperatureModifier.FROZEN, replacement.value().climateSettings.temperatureModifier());
        assertTrue(replacement.is(Biomes.FROZEN_OCEAN), "Native frozen-ocean checks must accept the variant");
        assertTrue(replacement.is(NativeTectonicClimate.ICE_FREE_FROZEN_OCEAN));
        assertEquals(original.tags().toList(), replacement.tags().toList());
        assertSame(original.components(), replacement.components());
        assertSame(original.value().getAttributes(), replacement.value().getAttributes());
        assertSame(original.value().getGenerationSettings(), replacement.value().getGenerationSettings());
        assertSame(original.value().getSpecialEffects(), replacement.value().getSpecialEffects());
        int registeredBiomes = biomes.size();
        var oceanTag = biomes.getOrThrow(BiomeTags.IS_OCEAN);
        assertEquals(3, oceanTag.size());
        NativeTectonicClimate.initialize(biomes);
        assertEquals(registeredBiomes, biomes.size());
        assertSame(oceanTag, biomes.getOrThrow(BiomeTags.IS_OCEAN));
        assertEquals(3, oceanTag.size());
    }

    @Test void legacyBiomePalettesRetainTheirKeyAndClimateAcrossSerialization() throws Exception {
        var legacy = biomes.getOrThrow(NativeTectonicClimate.LEGACY_ICE_FREE_FROZEN_OCEAN);
        var current = biomes.getOrThrow(NativeTectonicClimate.ICE_FREE_FROZEN_OCEAN);
        var original = biomes.getOrThrow(Biomes.FROZEN_OCEAN);
        assertEquals(TerrainNamespace.LEGACY, legacy.key().identifier().getNamespace());
        assertNotSame(legacy.value(), current.value());
        assertEquals(current.value().getBaseTemperature(), legacy.value().getBaseTemperature());
        assertEquals(original.tags().toList(), legacy.tags().toList());
        assertSame(original.components(), legacy.components());
        assertTrue(legacy.is(Biomes.FROZEN_OCEAN));
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        var encoded = BiomeSource.CODEC.encodeStart(ops, new FixedBiomeSource(legacy)).getOrThrow();
        var decoded = BiomeSource.CODEC.parse(ops, encoded).getOrThrow();
        assertEquals(java.util.Set.of(legacy), decoded.possibleBiomes());
        int count = biomes.size();
        NativeTectonicClimate.initialize(biomes);
        assertEquals(count, biomes.size());
        assertSame(legacy, biomes.getOrThrow(NativeTectonicClimate.LEGACY_ICE_FREE_FROZEN_OCEAN));
    }

    @Test void onlyTheSelectedWorldSourceUsesTheIceFreeOceanAndItSurvivesSerialization() {
        var selected = NativeTectonicClimate.biomeSource(lookup,
                options(Map.of("oceans.remove_frozen_ocean_ice", true)));
        var untouched = NativeTectonicClimate.biomeSource(lookup,
                options(Map.of("oceans.remove_frozen_ocean_ice", false)));
        var replacement = biomes.getOrThrow(NativeTectonicClimate.ICE_FREE_FROZEN_OCEAN);
        var original = biomes.getOrThrow(Biomes.FROZEN_OCEAN);
        assertTrue(selected.possibleBiomes().contains(replacement));
        assertFalse(selected.possibleBiomes().contains(original));
        assertTrue(untouched.possibleBiomes().contains(original));
        assertFalse(untouched.possibleBiomes().contains(replacement));
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        var encoded = BiomeSource.CODEC.encodeStart(ops, selected).getOrThrow();
        var decoded = BiomeSource.CODEC.parse(ops, encoded).getOrThrow();
        assertTrue(decoded.possibleBiomes().contains(replacement));
        assertFalse(decoded.possibleBiomes().contains(original));
    }

    @Test void realMonumentCandidatesAcceptBothTaggedAndExplicitBiomeLists() throws Exception {
        Holder<Biome> original = biomes.getOrThrow(Biomes.FROZEN_OCEAN);
        Holder<Biome> replacement = biomes.getOrThrow(NativeTectonicClimate.ICE_FREE_FROZEN_OCEAN);
        var manager = manager(replacement);
        NativeTectonicClimate.attach(new Object(), new Object(), manager,
                options(Map.of("general.snow_start_offset", 512)));
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), 123456789L, settings.value());
        var sampler = random.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
        HolderSet<Biome> explicit = HolderSet.direct(original);
        HolderSet<Biome> tagged = biomes.getOrThrow(BiomeTags.IS_OCEAN);
        for (Holder<Biome> candidate : List.of(replacement, manager.getBiome(BlockPos.ZERO))) {
            assertTrue(explicit.contains(candidate), "Explicit native structure biomes must accept climate holders");
            assertTrue(tagged.contains(candidate), "Tagged native structure biomes must retain ocean membership");
            for (HolderSet<Biome> whitelist : List.of(explicit, tagged)) {
                var structure = new OceanMonumentStructure(new Structure.StructureSettings(whitelist));
                var source = new FixedBiomeSource(candidate);
                var generator = new NoiseBasedChunkGenerator(source, settings);
                var context = new Structure.GenerationContext(RegistryAccess.EMPTY, generator, source,
                        sampler, random, null, 123456789L, new ChunkPos(0, 0),
                        LevelHeightAccessor.create(-64, 384), structure.biomes()::contains);
                assertTrue(structure.findValidGenerationPoint(context).isPresent(),
                        "The real monument surrounding-biome and candidate checks must accept the climate variant");
                var rejected = new Structure.GenerationContext(RegistryAccess.EMPTY, generator, source,
                        sampler, random, null, 123456789L, new ChunkPos(0, 0),
                        LevelHeightAccessor.create(-64, 384),
                        HolderSet.direct(biomes.getOrThrow(Biomes.PLAINS))::contains);
                assertTrue(structure.findValidGenerationPoint(rejected).isEmpty(),
                        "The alias must not allow unrelated structure biome lists");
            }
        }
        assertFalse(HolderSet.direct(replacement).contains(original),
                "The variant must not make vanilla worlds pass variant-only biome lists");
    }

    @Test void nativeBiomeQueriesUseTheWorldSnowlineWithoutChangingVanillaHolders() throws Exception {
        Holder<Biome> original = biomes.getOrThrow(Biomes.FOREST);
        var selected = manager(original);
        var untouched = manager(original);
        Object world = new Object();
        NativeTectonicClimate.attach(world, new Object(), selected,
                options(Map.of("general.snow_start_offset", 512)));
        BlockPos mountain = new BlockPos(19, 700, -37);
        Holder<Biome> adjusted = selected.getBiome(mountain);
        assertNotSame(original.value(), adjusted.value());
        assertSame(adjusted, selected.getNoiseBiomeAtQuart(0, 0, 0));
        assertSame(original, untouched.getBiome(mountain));
        assertEquals(original.unwrapKey(), adjusted.unwrapKey());
        assertEquals(original.tags().toList(), adjusted.tags().toList());
        assertEquals(original.components(), adjusted.components());
        assertEquals(original.value().getTemperature(mountain.below(512), 63),
                adjusted.value().getTemperature(mountain, 63));
        assertEquals(Biome.Precipitation.SNOW, original.value().getPrecipitationAt(mountain, 63));
        assertEquals(Biome.Precipitation.RAIN, adjusted.value().getPrecipitationAt(mountain, 63));
        assertEquals(new SpawnInfo(575), hook("adjustSpawnInfo", new Class<?>[]{Object.class, Object.class},
                world, new SpawnInfo(63)));
    }

    @Test void replacingAndDetachingAWorldReleasesPoliciesAndAlreadyInheritedResolvers() throws Exception {
        Holder<Biome> original = biomes.getOrThrow(Biomes.FOREST);
        var selected = manager(original);
        var inherited = manager(original);
        var world = new ManagedWorld(selected);
        Object firstGenerator = new Object();
        NativeTectonicClimate.attach(world, firstGenerator, selected,
                options(Map.of("general.snow_start_offset", 512, "oceans.monument_offset", -30)));
        hook("inheritBiomeManager", new Class<?>[]{Object.class}, new Region(world, inherited));
        BlockPos mountain = new BlockPos(19, 700, -37);
        Holder<Biome> first = selected.getBiome(mountain);
        assertSame(first, inherited.getBiome(mountain));

        Object replacementGenerator = new Object();
        NativeTectonicClimate.attach(world, replacementGenerator, selected,
                options(Map.of("general.snow_start_offset", 128, "oceans.monument_offset", -12)));
        Holder<Biome> replacement = selected.getBiome(mountain);
        assertNotSame(first.value(), replacement.value());
        assertEquals(original.value().getTemperature(mountain.below(128), 63),
                replacement.value().getTemperature(mountain, 63));
        assertSame(mountain, hook("temperaturePosition", new Class<?>[]{Object.class, Object.class},
                first.value(), mountain));
        assertSame(original, inherited.getBiome(mountain), "Old region resolvers must stop creating climate copies");
        assertEquals(39, monumentY(firstGenerator));
        assertEquals(27, monumentY(replacementGenerator));

        NativeTectonicClimate.detach(world);
        assertSame(original, selected.getBiome(mountain));
        assertSame(mountain, hook("temperaturePosition", new Class<?>[]{Object.class, Object.class},
                replacement.value(), mountain));
        SpawnInfo packet = new SpawnInfo(63);
        assertSame(packet, hook("adjustSpawnInfo", new Class<?>[]{Object.class, Object.class}, world, packet));
        assertEquals(39, monumentY(replacementGenerator));
        assertDoesNotThrow(() -> NativeTectonicClimate.detach(world));
    }

    @Test void zeroSnowOffsetKeepsRegisteredHoldersAndModuleCloseReleasesEveryWorld() throws Exception {
        Holder<Biome> original = biomes.getOrThrow(Biomes.FROZEN_OCEAN);
        var first = manager(original);
        var second = manager(original);
        Object firstWorld = new Object(), secondWorld = new Object();
        NativeTectonicClimate.attach(firstWorld, new Object(), first,
                options(Map.of("general.snow_start_offset", 0)));
        NativeTectonicClimate.attach(secondWorld, new Object(), second,
                options(Map.of("general.snow_start_offset", 512)));
        assertSame(original, first.getBiome(BlockPos.ZERO));
        assertNotSame(original.value(), second.getBiome(BlockPos.ZERO).value());
        NativeTectonicClimate.close();
        assertSame(original, second.getBiome(BlockPos.ZERO));
        SpawnInfo packet = new SpawnInfo(63);
        assertSame(packet, hook("adjustSpawnInfo", new Class<?>[]{Object.class, Object.class}, secondWorld, packet));
    }

    private static BiomeManager manager(Holder<Biome> biome) {
        return new BiomeManager((x, y, z) -> biome, 123456789L);
    }

    private static TerrainOptions options(Map<String, Object> advanced) {
        return new TerrainOptions(1024, 2.8, 1.5, true, true, true, true, advanced);
    }

    private static int monumentY(Object generator) throws Exception {
        Object previous = hook("enterGenerator", new Class<?>[]{Object.class}, generator);
        try {
            return ((Box) hook("monumentBounds", new Class<?>[]{Object.class}, new Box(39))).minY();
        } finally {
            hook("leave", new Class<?>[]{Object.class}, previous);
        }
    }

    private static Object hook(String name, Class<?>[] signature, Object... arguments) throws Exception {
        return NativeTectonicClimate.hook(name, signature, arguments);
    }
}
