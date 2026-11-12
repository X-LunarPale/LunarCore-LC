package com.lunarboreal.terrain.nms;

import com.google.common.base.Suppliers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunarboreal.terrain.TectonicModel;
import com.lunarboreal.terrain.TerrainOptions;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.synth.Noise;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Applies the mod's decoration overlays to one generator, preserving the biome registry. */
public final class NativeTectonicFeatures {
    private static final JsonObject MODEL = load();
    private static final TagKey<Biome> SNOWY = TagKey.create(Registries.BIOME, Identifier.parse("c:is_snowy"));
    private static final Set<String> VANILLA_SNOWY = Set.of("snowy_plains", "ice_spikes", "snowy_taiga",
            "snowy_beach", "grove", "snowy_slopes", "jagged_peaks", "frozen_peaks");

    private NativeTectonicFeatures() {}

    public record Prepared(Function<Holder<Biome>, BiomeGenerationSettings> settings,
                           Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep,
                           int decoratedBiomes, int replacedOres) {}

    /** Decode and validate the complete feature order before any chunks start generating. */
    public static Prepared prepare(NoiseBasedChunkGenerator generator, RandomState random,
                                   HolderLookup.Provider registries, TerrainOptions options) {
        Map<String, Holder<PlacedFeature>> additions = new LinkedHashMap<>();
        additions.put("lichen", Holder.direct(placed("tectonic:underground_river/lichen", random, registries, options)));
        if (options.flag("continents.river_lanterns")) {
            additions.put("lanterns", Holder.direct(placed("tectonic:underground_river/lanterns", random, registries, options)));
        }
        if (options.flag("continents.river_ice")) {
            additions.put("ice", Holder.direct(placed("tectonic:underground_river/ice", random, registries, options)));
        }
        Map<ResourceKey<PlacedFeature>, Holder<PlacedFeature>> ores = new HashMap<>();
        if (options.flag("caves.ore_fix")) {
            MODEL.getAsJsonObject("oreFeatures").entrySet().forEach(entry -> ores.put(
                    ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(entry.getKey())),
                    Holder.direct(placed(entry.getValue().getAsJsonObject(), random, registries, options))));
        }
        Map<Holder<Biome>, BiomeGenerationSettings> settings = new HashMap<>();
        int decorated = 0;
        for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
            BiomeGenerationSettings original = biome.value().getGenerationSettings();
            BiomeGenerationSettings.PlainBuilder builder = new BiomeGenerationSettings.PlainBuilder();
            original.getCarvers().forEach(carver -> {
                String key = carver.unwrapKey().map(k -> k.identifier().toString()).orElse("");
                if (options.flag("caves.carvers_enabled") || !MODEL.getAsJsonObject("carvers").has(key)) {
                    builder.addCarver(carver);
                }
            });
            for (int step = 0; step < original.features().size(); step++) {
                for (Holder<PlacedFeature> feature : original.features().get(step)) {
                    Holder<PlacedFeature> replacement = feature.unwrapKey().map(ores::get).orElse(null);
                    builder.addFeature(step, replacement == null ? feature : replacement);
                }
            }
            if (biome.is(BiomeTags.IS_OVERWORLD)) {
                decorated++;
                for (var addition : additions.entrySet()) {
                    if (!addition.getKey().equals("ice") || isSnowy(biome)) {
                        builder.addFeature(GenerationStep.Decoration.VEGETAL_DECORATION, addition.getValue());
                    }
                }
            }
            settings.put(biome, builder.build());
        }
        Map<Holder<Biome>, BiomeGenerationSettings> immutable = Map.copyOf(settings);
        Map<ResourceKey<Biome>, BiomeGenerationSettings> keyed = new HashMap<>();
        settings.forEach((biome, value) -> biome.unwrapKey().ifPresent(key -> keyed.put(key, value)));
        Function<Holder<Biome>, BiomeGenerationSettings> getter =
                biome -> biome.unwrapKey().map(keyed::get)
                        .orElseGet(() -> immutable.getOrDefault(biome, biome.value().getGenerationSettings()));
        Supplier<List<FeatureSorter.StepFeatureData>> order = Suppliers.memoize(() ->
                FeatureSorter.buildFeaturesPerStep(List.copyOf(generator.getBiomeSource().possibleBiomes()),
                        biome -> getter.apply(biome).features(), true));
        order.get();
        return new Prepared(getter, order, decorated, ores.size());
    }

    public static void install(NoiseBasedChunkGenerator generator, Prepared prepared) throws ReflectiveOperationException {
        Field getter = ChunkGenerator.class.getDeclaredField("generationSettingsGetter");
        Field featureOrder = ChunkGenerator.class.getDeclaredField("featuresPerStep");
        getter.setAccessible(true);
        featureOrder.setAccessible(true);
        getter.set(generator, prepared.settings());
        featureOrder.set(generator, prepared.featuresPerStep());
        TerrainFluidPicker.install(generator);
        generator.validate();
    }

    public static PlacedFeature placed(String key, RandomState random, HolderLookup.Provider registries, TerrainOptions options) {
        JsonObject source = MODEL.getAsJsonObject("placedFeatures").getAsJsonObject(key);
        if (source == null) throw new IllegalArgumentException("Unknown upstream placed feature: " + key);
        return placed(source, random, registries, options);
    }

    private static PlacedFeature placed(JsonObject source, RandomState random, HolderLookup.Provider registries,
                                        TerrainOptions options) {
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        String featureKey = source.get("feature").getAsString();
        JsonObject ownedFeature = MODEL.getAsJsonObject("features").getAsJsonObject(featureKey);
        Holder<Feature> feature = ownedFeature == null
                ? registries.lookupOrThrow(Registries.FEATURE).getOrThrow(
                        ResourceKey.create(Registries.FEATURE, Identifier.parse(featureKey)))
                : Holder.direct(Feature.DIRECT_CODEC.parse(ops, ownedFeature).getOrThrow());
        List<PlacementModifier> modifiers = new ArrayList<>();
        for (JsonElement value : source.getAsJsonArray("placement")) {
            JsonObject modifier = value.getAsJsonObject();
            String type = modifier.get("type").getAsString();
            if (type.equals("lithostitched:condition")) {
                JsonObject condition = modifier.getAsJsonObject("condition");
                String densityKey = condition.get("density_function").getAsString();
                DensityFunction function = DensityFunction.CODEC.parse(ops,
                        TectonicModel.densityFunction(densityKey, options)).getOrThrow();
                modifiers.add(new DensityCondition(random.getSampler(function),
                        condition.get("min_inclusive").getAsFloat(), condition.get("max_inclusive").getAsFloat()));
            } else if (type.equals("lithostitched:noise_slope")) {
                Noise noise = random.getOrCreateNoise(ResourceKey.create(Registries.NOISE,
                        Identifier.parse(modifier.get("noise").getAsString())));
                modifiers.add(new NoiseSlope(noise, modifier.get("slope").getAsFloat(),
                        modifier.get("offset").getAsInt(), modifier.get("xz_scale").getAsFloat(),
                        modifier.get("y_scale").getAsFloat()));
            } else if (type.equals("tectonic:height_stabilized_count")) {
                modifiers.add(new HeightStabilizedCount(
                        VerticalAnchor.CODEC.parse(ops, modifier.get("max_y")).getOrThrow(),
                        modifier.get("count_per_section").getAsFloat(), modifier.get("biased_to_bottom").getAsBoolean()));
            } else {
                modifiers.add(PlacementModifier.CODEC.parse(ops, modifier).getOrThrow());
            }
        }
        return new PlacedFeature(feature, List.copyOf(modifiers));
    }

    private static boolean isSnowy(Holder<Biome> biome) {
        return biome.is(SNOWY) || biome.unwrapKey().map(key ->
                key.identifier().getNamespace().equals("minecraft")
                        && VANILLA_SNOWY.contains(key.identifier().getPath())).orElse(false);
    }

    private record DensityCondition(DensitySampler sampler, float min, float max) implements PlacementModifier {
        @Override public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output) {
            float value = sampler.sampleValue(SamplerContext.EMPTY_UNCACHED, origin.getX(), origin.getY(), origin.getZ());
            if (value >= min && value <= max) output.accept(origin);
        }

        @Override public MapCodec<? extends PlacementModifier> codec() { return MapCodec.unit(this); }
    }

    private record NoiseSlope(Noise noise, float slope, int offset, float xzScale, float yScale) implements PlacementModifier {
        @Override public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output) {
            int count = (int) Math.ceil(noise.get(origin.getX() * xzScale, origin.getY() * yScale,
                    origin.getZ() * xzScale) * slope) + offset;
            for (int i = 0; i < count; i++) output.accept(origin);
        }

        @Override public MapCodec<? extends PlacementModifier> codec() { return MapCodec.unit(this); }
    }

    static record HeightStabilizedCount(VerticalAnchor maxY, float countPerSection,
                                       boolean biasedToBottom) implements PlacementModifier {
        @Override public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output) {
            int top = maxY.resolveY(context);
            candidates(context.getMinGenY(), top, countPerSection, biasedToBottom, random, origin).forEach(output);
        }

        @Override public MapCodec<? extends PlacementModifier> codec() { return MapCodec.unit(this); }
    }

    static List<BlockPos> candidates(int minY, int maxY, float countPerSection, boolean biasedToBottom,
                                     RandomSource random, BlockPos origin) {
        int span = maxY - minY;
        if (span <= 0) return List.of();
        int count = (int) Math.ceil((float) span / 16F * countPerSection);
        List<BlockPos> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int y = biasedToBottom
                    ? minY + random.nextInt(random.nextIntBetweenInclusive(span / 2, span))
                    : random.nextIntBetweenInclusive(minY, maxY);
            positions.add(origin.atY(y));
        }
        return positions;
    }

    private static JsonObject load() {
        try (InputStream input = NativeTectonicFeatures.class.getResourceAsStream("/upstream/tectonic-features.json")) {
            if (input == null) throw new IOException("Missing pinned Tectonic features");
            JsonObject model = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!TectonicModel.UPSTREAM_SHA512.equals(model.get("sha512").getAsString())) {
                throw new IOException("Unexpected upstream feature source");
            }
            return model;
        } catch (IOException | RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
