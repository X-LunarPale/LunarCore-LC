package com.lunarboreal.terrain.nms;

import com.google.gson.JsonParser;
import com.lunarboreal.terrain.TectonicModel;
import com.lunarboreal.terrain.TerrainOptions;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Native codecs and terrain columns only: no server, sockets, worlds or players are started. */
class OfflineNativeTectonicTest {
    private static HolderLookup.Provider vanilla;
    private static HolderLookup.Provider lookup;
    private static MappedRegistry<NormalNoise> noises;
    private static MappedRegistry<DimensionType> dimensions;

    @Test void requiredNativeLayoutIsAvailable() {
        assertDoesNotThrow(NativeTectonicBridge::requireNativeLayout);
    }

    @BeforeAll static void loadNativeRegistries() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NativeTectonicFunctions.registerTypes();
        vanilla = VanillaRegistries.createWorldLookup();
        bindFixtureBiomeTags();
        noises = copyRegistry(Registries.NOISE, vanilla.lookupOrThrow(Registries.NOISE));
        dimensions = copyRegistry(Registries.DIMENSION_TYPE, vanilla.lookupOrThrow(Registries.DIMENSION_TYPE));
        int originalNoiseId = noises.getId(noises.getValue(Identifier.parse("minecraft:continentalness")));
        var jsonOps = vanilla.createSerializationContext(JsonOps.INSTANCE);
        Map<ResourceKey<NormalNoise>, NormalNoise> addedNoises = new LinkedHashMap<>();
        TectonicModel.noises().forEach((name, json) -> addedNoises.put(
                ResourceKey.create(Registries.NOISE, Identifier.parse(name)),
                NormalNoise.DIRECT_CODEC.parse(jsonOps, json).getOrThrow()));
        appendEntries(noises, addedNoises);
        assertEquals(originalNoiseId,
                noises.getId(noises.getValue(Identifier.parse("minecraft:continentalness"))));

        DimensionType original = dimensions.getValueOrThrow(BuiltinDimensionTypes.OVERWORLD);
        Method withHeight = NativeTectonicBridge.class.getDeclaredMethod("withHeight", DimensionType.class, int.class);
        withHeight.setAccessible(true);
        Map<ResourceKey<DimensionType>, DimensionType> addedDimensions = new LinkedHashMap<>();
        for (int height : TerrainOptions.HEIGHTS) {
            addedDimensions.put(dimensionKey(height), (DimensionType) withHeight.invoke(null, original, height));
        }
        appendEntries(dimensions, addedDimensions);
        lookup = HolderLookup.Provider.create(Stream.concat(
                vanilla.listRegistries().filter(row -> !row.key().equals(Registries.NOISE)
                        && !row.key().equals(Registries.DIMENSION_TYPE)),
                Stream.of(noises, dimensions)));
    }

    @Test void savesAndDecodesEverySupportedHeightWithoutChangingVanilla(@TempDir Path directory) throws IOException {
        var jsonOps = lookup.createSerializationContext(JsonOps.INSTANCE);
        var nbtOps = RegistryOps.create(NbtOps.INSTANCE, lookup);
        for (int height : TerrainOptions.HEIGHTS) {
            TerrainOptions options = new TerrainOptions(height, 1.8, 1.2, true, true, true, true);
            NoiseGeneratorSettings settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(
                    jsonOps, TectonicModel.noiseSettings(options)).getOrThrow();
            assertEquals(-64, settings.noiseSettings().minY());
            assertEquals(height, settings.noiseSettings().height());
            var generator = generator(settings);
            var source = new WorldGenSettings(new net.minecraft.world.level.levelgen.WorldOptions(123456789L, true, false),
                    new WorldDimensions(Map.of(LevelStem.OVERWORLD,
                            new LevelStem(dimensions.getOrThrow(dimensionKey(height)), generator))));
            var encoded = WorldGenSettings.CODEC.encodeStart(nbtOps, source).getOrThrow();
            CompoundTag savedData = new CompoundTag();
            savedData.put("data", encoded);
            NbtUtils.addCurrentDataVersion(savedData);
            Path savedFile = directory.resolve("worldgen-" + height + ".dat");
            NbtIo.writeCompressed(savedData, savedFile);
            // Match native saved-data reads, including the remaining 512-level nesting limit.
            CompoundTag reloaded = NbtIo.readCompressed(savedFile, NbtAccounter.unlimitedHeap());
            assertEquals(encoded, reloaded.getCompoundOrEmpty("data"));
            WorldGenSettings decoded = WorldGenSettings.CODEC.parse(
                    nbtOps, reloaded.getCompoundOrEmpty("data")).getOrThrow();
            LevelStem stem = decoded.dimensions().get(LevelStem.OVERWORLD).orElseThrow();
            assertEquals(123456789L, decoded.options().seed());
            assertTrue(stem.type().is(dimensionKey(height)));
            assertEquals(height, stem.type().value().height());
            assertEquals(NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(jsonOps, settings).getOrThrow(),
                    NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(jsonOps,
                            ((NoiseBasedChunkGenerator) stem.generator()).generatorSettings().value()).getOrThrow());
        }
        assertEquals(384, vanilla.lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(BuiltinDimensionTypes.OVERWORLD).value().height());
        assertEquals(384, dimensions.getValueOrThrow(BuiltinDimensionTypes.OVERWORLD).height());
    }

    @Test void generatesSolidNativeTerrainAboveTheVanillaCeiling() {
        TerrainOptions options = new TerrainOptions(1024, 2.8, 1.5, true, true, true, true);
        NoiseGeneratorSettings settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(
                lookup.createSerializationContext(JsonOps.INSTANCE), TectonicModel.noiseSettings(options)).getOrThrow();
        var generator = generator(settings);
        var random = RandomState.create(noises, 123456789L, settings);
        var height = LevelHeightAccessor.create(-64, options.height());
        int highest = -64;
        int highestX = 0;
        int highestZ = 0;
        // Offline bootstrap has no block tags; WORLD_SURFACE_WG uses the native non-air predicate.
        for (int x = -3072; x <= 3072; x += 512) {
            for (int z = -3072; z <= 3072; z += 512) {
                int value = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
                assertTrue(value >= -64 && value <= options.height() - 64, "Native height must stay inside the dimension");
                if (value > highest) {
                    highest = value;
                    highestX = x;
                    highestZ = z;
                }
            }
        }
        System.out.println("Offline native terrain: highest=" + highest + " x=" + highestX + " z=" + highestZ);
        assertTrue(highest > 321, "Pinned terrain seed must produce solid ground above Y=320");
        var column = generator.getBaseColumn(highestX, highestZ, height, random);
        assertEquals(settings.defaultBlock(), column.getBlock(highest - 1),
                "The sampled peak must contain the native terrain block, not fluid");
        assertEquals(highest, generator.getBaseHeight(
                highestX, highestZ, Heightmap.Types.WORLD_SURFACE_WG, height, RandomState.create(noises, 123456789L, settings)));
    }

    @Test void nativeContinentalLayoutDiffersFromVanillaAtTheSameSeed() {
        TerrainOptions options = new TerrainOptions(384, 1.125, 1, true, true, true, true);
        NoiseGeneratorSettings tectonicSettings = NoiseGeneratorSettings.DIRECT_CODEC.parse(
                lookup.createSerializationContext(JsonOps.INSTANCE), TectonicModel.noiseSettings(options)).getOrThrow();
        NoiseGeneratorSettings vanillaSettings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var tectonic = generator(tectonicSettings);
        var overworld = generator(vanillaSettings);
        var tectonicRandom = RandomState.create(noises, 123456789L, tectonicSettings);
        var vanillaRandom = RandomState.create(noises, 123456789L, vanillaSettings);
        var height = LevelHeightAccessor.create(-64, 384);
        int x = 8384;
        int z = 6144;
        int tectonicY = tectonic.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, tectonicRandom);
        int vanillaY = overworld.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, vanillaRandom);
        assertTrue(Math.abs(tectonicY - vanillaY) >= 8,
                "Tectonic continental layout must differ from vanilla at the same seed");
    }

    @Test void installsFeatureOverlaysOnlyOnTheirOwnNativeGenerator() throws Exception {
        TerrainOptions options = new TerrainOptions(1024, 2.8, 1.5, true, true, true, true,
                Map.of("caves.ore_fix", true, "caves.carvers_enabled", false, "continents.river_ice", true));
        NoiseGeneratorSettings settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(
                lookup.createSerializationContext(JsonOps.INSTANCE), TectonicModel.noiseSettings(options)).getOrThrow();
        NoiseBasedChunkGenerator selected = generator(settings);
        NoiseBasedChunkGenerator untouched = generator(settings);
        var originalGetter = untouched.generationSettingsGetter;
        var random = RandomState.create(noises, 123456789L, settings);
        var prepared = NativeTectonicFeatures.prepare(selected, random, lookup, options);
        NativeTectonicFeatures.install(selected, prepared);

        assertSame(prepared.settings(), selected.generationSettingsGetter);
        assertSame(originalGetter, untouched.generationSettingsGetter);
        assertEquals(selected.getBiomeSource().possibleBiomes().size(), prepared.decoratedBiomes());
        assertEquals(12, prepared.replacedOres());
        for (var biome : selected.getBiomeSource().possibleBiomes()) {
            assertSame(biome.value().getGenerationSettings(), untouched.getBiomeGenerationSettings(biome));
            assertFalse(prepared.settings().apply(biome).getCarvers().iterator().hasNext());
        }
        for (String name : new String[]{"ice", "lanterns", "lichen"}) {
            var placed = NativeTectonicFeatures.placed("tectonic:underground_river/" + name, random, lookup, options);
            assertFalse(placed.placement().isEmpty());
            assertNotNull(placed.feature().value());
        }
    }

    @Test void samplesNamedTerrainRegionsThroughNativeCodecs() {
        record Bound(String key, double min, double max) {
            boolean contains(double value) { return value >= min && value <= max; }
        }
        Map<String, List<Bound>> targets = new LinkedHashMap<>();
        targets.put("mountain_range", List.of(
                new Bound("noise/continent/erosion_folded", 0, 0.05),
                new Bound("noise/raw_continents", 0.1, 64)));
        targets.put("underground_river", List.of(
                new Bound("noise/continent/ridges_folded", 0, 0.025),
                new Bound("noise/raw_continents", -0.1, 64),
                new Bound("noise/continent/erosion_folded", 0, 0.225)));
        targets.put("jungle_pillars", List.of(
                new Bound("noise/continent/ridges", -0.75, -0.2),
                new Bound("noise/vegetation_index", 4, 5),
                new Bound("noise/temperature_index", 4, 4),
                new Bound("noise/region_selector", 0.1, 64),
                new Bound("noise/continent/erosion", -0.8, -0.45),
                new Bound("noise/raw_continents", 0.1, 64)));
        targets.put("rolling_hills", List.of(
                new Bound("noise/continent/ridges_folded", 0.2, 64),
                new Bound("noise/vegetation_index", 0, 2),
                new Bound("noise/temperature_index", 0, 3),
                new Bound("noise/region_selector", 0.1, 64),
                new Bound("noise/continent/erosion", -0.8, -0.45),
                new Bound("noise/raw_continents", 0.1, 64)));
        targets.put("badlands_canyon", List.of(
                new Bound("noise/continent/ridges_folded", 0.2, 64),
                new Bound("noise/temperature_index", 5, 5),
                new Bound("noise/region_selector", -64, 0),
                new Bound("noise/continent/erosion", 0.45, 0.8),
                new Bound("noise/raw_continents", 0.35, 64)));
        targets.put("badlands_plateaus", List.of(
                new Bound("noise/continent/ridges_folded", 0.2, 64),
                new Bound("noise/temperature_index", 5, 5),
                new Bound("noise/region_selector", -64, 0),
                new Bound("noise/continent/erosion", -8, -0.45),
                new Bound("noise/raw_continents", 0.35, 64)));
        targets.put("desert_dunes", List.of(
                new Bound("noise/continent/ridges_folded", 0.2, 64),
                new Bound("noise/temperature_index", 5, 5),
                new Bound("noise/region_selector", 0.1, 64),
                new Bound("noise/continent/erosion", 0.45, 0.8),
                new Bound("noise/raw_continents", 0.1, 64)));
        targets.put("cherry_valley", List.of(
                new Bound("noise/continent/ridges_folded", 0.4, 64),
                new Bound("noise/vegetation_index", 0, 2),
                new Bound("noise/temperature_index", 3, 3),
                new Bound("noise/region_selector", -64, 0),
                new Bound("noise/continent/erosion", -0.8, -0.45),
                new Bound("noise/raw_continents", 0.35, 64)));
        TerrainOptions options = new TerrainOptions(1024, 2.8, 1.5, true, true, true, true);
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        NoiseGeneratorSettings settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(
                ops, TectonicModel.noiseSettings(options)).getOrThrow();
        var random = RandomState.create(noises, 123456789L, settings);
        Map<String, DensitySampler> samplers = new LinkedHashMap<>();
        targets.values().stream().flatMap(List::stream).map(Bound::key).distinct().forEach(key ->
                samplers.put(key, random.getSampler(DensityFunction.CODEC.parse(
                        ops, TectonicModel.densityFunction("tectonic:" + key, options)).getOrThrow())));
        var context = SamplerContext.builder().enableCaches().build();
        Map<String, Double> values = new LinkedHashMap<>();
        java.util.function.Consumer<BlockPos> sample = position -> samplers.forEach((key, sampler) -> {
            float value = sampler.sampleValue(context, position.getX(), position.getY(), position.getZ());
            assertTrue(Float.isFinite(value), key);
            // The shipped locate command rounds to three decimals before applying its inclusive bounds.
            values.put(key, Math.round((double) value * 1000) / 1000d);
        });
        Map<String, BlockPos> representatives = Map.of(
                "mountain_range", new BlockPos(-1088, 0, -1408),
                "underground_river", new BlockPos(-640, 0, -1408),
                "rolling_hills", new BlockPos(1408, 0, -2688),
                "jungle_pillars", new BlockPos(1344, 0, -4160),
                "desert_dunes", new BlockPos(8384, 0, 6144),
                "cherry_valley", new BlockPos(16256, 0, -5952));
        for (var target : targets.entrySet()) {
            BlockPos position = representatives.get(target.getKey());
            if (position == null) continue;
            sample.accept(position);
            Map<String, Double> matched = new LinkedHashMap<>();
            for (Bound bound : target.getValue()) {
                double value = values.get(bound.key());
                assertTrue(bound.contains(value), target.getKey() + " " + bound.key() + "=" + value);
                matched.put(bound.key(), value);
            }
            System.out.println("Native terrain region: " + target.getKey() + " at " + position
                    + " values=" + matched);
        }
        Map<String, BlockPos> additional = new LinkedHashMap<>();
        for (int x = -256; x <= 256; x += 64) {
            for (int z = -256; z <= 256; z += 64) {
                BlockPos position = new BlockPos(x, 0, z);
                sample.accept(position);
                for (String name : List.of("badlands_canyon", "badlands_plateaus")) {
                    if (targets.get(name).stream().allMatch(bound -> bound.contains(values.get(bound.key())))) {
                        additional.putIfAbsent(name, position);
                    }
                }
            }
        }
        for (String name : List.of("badlands_canyon", "badlands_plateaus")) {
            System.out.println("Native terrain region probe: " + name + " representative=" + additional.get(name)
                    + "; native codecs and 81 finite samples validated, region existence is not asserted");
        }
        // Matching a terrain region does not prove that any decoration or complete chunk was generated.
        assertEquals(6, representatives.size());
    }

    private static void bindFixtureBiomeTags() throws Exception {
        Set<Identifier> overworld = new HashSet<>();
        try (var input = OfflineNativeTectonicTest.class.getResourceAsStream(
                "/data/minecraft/tags/worldgen/biome/is_overworld.json")) {
            assertNotNull(input, "The native Paper biome tag must be on the test classpath");
            JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonArray("values").forEach(value -> overworld.add(Identifier.parse(value.getAsString())));
        }
        Set<ResourceKey<Biome>> snowy = Set.of(Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA,
                Biomes.SNOWY_BEACH, Biomes.GROVE, Biomes.SNOWY_SLOPES, Biomes.JAGGED_PEAKS, Biomes.FROZEN_PEAKS);
        TagKey<Biome> snowyTag = TagKey.create(Registries.BIOME, Identifier.parse("c:is_snowy"));
        // The offline registry builder leaves holder tags unbound; the live server binds them on load.
        Method bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
        bindTags.setAccessible(true);
        for (var biome : vanilla.lookupOrThrow(Registries.BIOME).listElements().toList()) {
            var tags = new ArrayList<TagKey<Biome>>();
            if (overworld.contains(biome.key().identifier())) tags.add(BiomeTags.IS_OVERWORLD);
            if (snowy.contains(biome.key())) tags.add(snowyTag);
            bindTags.invoke(biome, tags);
        }
    }

    private static NoiseBasedChunkGenerator generator(NoiseGeneratorSettings settings) {
        return new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(
                lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), Holder.direct(settings));
    }

    private static ResourceKey<DimensionType> dimensionKey(int height) {
        return ResourceKey.create(Registries.DIMENSION_TYPE,
                Identifier.fromNamespaceAndPath("lbr_terrain", "overworld_" + height));
    }

    private static <T> MappedRegistry<T> copyRegistry(ResourceKey<? extends Registry<T>> key,
                                                    HolderLookup.RegistryLookup<T> source) {
        var result = new MappedRegistry<T>(key, Lifecycle.stable());
        source.listElements().forEach(holder -> result.register(holder.key(), holder.value(),
                new RegistrationInfo(Optional.empty(), Lifecycle.stable())));
        result.freeze();
        return result;
    }

    private static <T> void appendEntries(Registry<T> registry, Map<ResourceKey<T>, T> entries) throws Exception {
        Method append = NativeTectonicBridge.class.getDeclaredMethod("appendEntries", Registry.class, Map.class);
        append.setAccessible(true);
        append.invoke(null, registry, entries);
    }
}
