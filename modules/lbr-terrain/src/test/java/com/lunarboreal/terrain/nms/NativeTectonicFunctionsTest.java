package com.lunarboreal.terrain.nms;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunarboreal.terrain.TectonicModel;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NativeTectonicFunctionsTest {
    private static HolderLookup.Provider lookup;
    private static MappedRegistry<NormalNoise> noises;
    private static NoiseGeneratorSettings vanillaSettings;

    @BeforeAll static void setup() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NativeTectonicFunctions.registerTypes();
        var vanilla = VanillaRegistries.createWorldLookup();
        noises = new MappedRegistry<>(Registries.NOISE, Lifecycle.stable());
        var registration = new RegistrationInfo(Optional.empty(), Lifecycle.stable());
        vanilla.lookupOrThrow(Registries.NOISE).listElements().forEach(holder ->
                noises.register(holder.key(), holder.value(), registration));
        noises.freeze();
        Map<ResourceKey<NormalNoise>, NormalNoise> additions = new LinkedHashMap<>();
        TectonicModel.noises().forEach((name, json) -> additions.put(
                ResourceKey.create(Registries.NOISE, Identifier.parse(name)),
                NormalNoise.DIRECT_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()));
        NativeTectonicBridge.appendEntries(noises, additions);
        lookup = HolderLookup.Provider.create(Stream.concat(
                vanilla.listRegistries().filter(row -> !row.key().equals(Registries.NOISE)), Stream.of(noises)));
        vanillaSettings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
    }

    @Test void repeatedRegistrationPreservesNativeCodecIdentities() throws Exception {
        var nativeNoise = BuiltInRegistries.DENSITY_FUNCTION_TYPE.getValue(Identifier.parse("minecraft:noise"));
        var nativeSteep = BuiltInRegistries.MATERIAL_CONDITION_TYPE.getValue(Identifier.parse("minecraft:steep"));
        int noiseId = BuiltInRegistries.DENSITY_FUNCTION_TYPE.getId(nativeNoise);
        int steepId = BuiltInRegistries.MATERIAL_CONDITION_TYPE.getId(nativeSteep);
        int noiseCount = BuiltInRegistries.DENSITY_FUNCTION_TYPE.size();
        int conditionCount = BuiltInRegistries.MATERIAL_CONDITION_TYPE.size();
        NativeTectonicFunctions.registerTypes();
        assertSame(NoiseFunction.CODEC, nativeNoise);
        assertSame(nativeNoise, BuiltInRegistries.DENSITY_FUNCTION_TYPE.getValue(Identifier.parse("minecraft:noise")));
        assertSame(nativeSteep, BuiltInRegistries.MATERIAL_CONDITION_TYPE.getValue(Identifier.parse("minecraft:steep")));
        assertEquals(noiseId, BuiltInRegistries.DENSITY_FUNCTION_TYPE.getId(nativeNoise));
        assertEquals(steepId, BuiltInRegistries.MATERIAL_CONDITION_TYPE.getId(nativeSteep));
        assertEquals(noiseCount, BuiltInRegistries.DENSITY_FUNCTION_TYPE.size());
        assertEquals(conditionCount, BuiltInRegistries.MATERIAL_CONDITION_TYPE.size());
    }

    @Test void slopeChecksBothGradientSignsAndEachBoundedAxis() throws Exception {
        var condition = new NativeTectonicFunctions.SlopeCondition(new InclusiveRange<>(4, 5));
        MaterialRuleContext context = context();
        var evaluator = condition.compile(context);
        int[][] gradients = {{4, 0}, {-4, 0}, {0, 5}, {0, -5}, {6, 5}, {5, 6}, {6, 3}, {-6, -3}, {3, 3}};
        boolean[] expected = {true, true, true, true, true, true, false, false, false};
        for (int index = 0; index < gradients.length; index++) {
            set(context, "surfaceGradientX", gradients[index][0]);
            set(context, "surfaceGradientZ", gradients[index][1]);
            set(context, "lastUpdateXZ", (long) index);
            assertEquals(expected[index], evaluator.test(), "Gradient case " + index);
        }
    }

    @Test void slopeCodecAcceptsUpstreamMissingBoundsAndRoundTrips() {
        var condition = MaterialCondition.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"type\":\"lbr_terrain:slope\",\"height_difference\":{\"min_inclusive\":4}}")).getOrThrow();
        assertEquals(new InclusiveRange<>(4, Integer.MAX_VALUE),
                ((NativeTectonicFunctions.SlopeCondition) condition).heightDifference());
        var encoded = MaterialCondition.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, condition).getOrThrow();
        assertEquals(encoded, MaterialCondition.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,
                MaterialCondition.DIRECT_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()).getOrThrow());
        var defaults = MaterialCondition.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"type\":\"lbr_terrain:slope\"}")).getOrThrow();
        assertEquals(condition, defaults);
    }

    @Test void allThreeSeedAliasesMatchTheOfficialHashAndRemainWorldLocal() {
        long seed = 123456789L;
        var random = RandomState.create(noises, seed, vanillaSettings);
        var controlBefore = random.getOrCreateNoise(net.minecraft.world.level.levelgen.Noises.CONTINENTALNESS)
                .get(21.5, 0.0, -42.75);
        for (String suffix : new String[]{"continentalness", "erosion", "ridge"}) {
            String name = "tectonic:parameter/" + suffix;
            var function = density("lbr_terrain:seeded_noise", name);
            Noise expected = referenceNoise(name, seed);
            var sampler = random.getSampler(function);
            for (int coordinate : new int[]{-8731, -128, 0, 19, 10421}) {
                assertEquals(expected.get(coordinate * 0.125 + 3.5, 0.0, -coordinate * 0.125 - 2.25),
                        sampler.sampleValue(SamplerContext.EMPTY_UNCACHED, coordinate, 87, -coordinate));
            }
            var duplicate = RandomState.create(noises, seed, vanillaSettings);
            assertEquals(random.sampleBlockValueUncached(function, 128, 87, 511),
                    duplicate.sampleBlockValueUncached(function, 128, 87, 511));
            assertNotEquals(random.sampleBlockValueUncached(function, 128, 87, 511),
                    RandomState.create(noises, seed + 1, vanillaSettings)
                            .sampleBlockValueUncached(function, 128, 87, 511));
            var encoded = DensityFunction.CODEC.encodeStart(lookup.createSerializationContext(JsonOps.INSTANCE), function)
                    .getOrThrow();
            assertEquals(function, DensityFunction.CODEC.parse(
                    lookup.createSerializationContext(JsonOps.INSTANCE), encoded).getOrThrow());
        }
        assertEquals(controlBefore, random.getOrCreateNoise(net.minecraft.world.level.levelgen.Noises.CONTINENTALNESS)
                .get(21.5, 0.0, -42.75));
        assertEquals(Identifier.parse("minecraft:ridge"),
                NativeTectonicFunctions.seedAlias(Identifier.parse("tectonic:parameter/ridge")));
        assertEquals(Identifier.parse("minecraft:continentalness"),
                NativeTectonicFunctions.seedAlias(Identifier.parse("minecraft:continentalness")));
    }

    @Test void configNoisePreservesPointAndVolumeArithmeticForBothModes() {
        String name = "tectonic:parameter/erosion";
        long seed = 123456789L;
        Noise expected = referenceNoise(name, seed);
        var volume = new DensityVolume(3, 2, 2, 10421, -64, -8731, 4, 8, 4);
        var random = RandomState.create(noises, seed, vanillaSettings);
        for (boolean smooth : new boolean[]{false, true}) {
            JsonObject json = noiseJson("lbr_terrain:config_noise", name);
            json.addProperty("xz_scale", 0.13f);
            json.addProperty("multiplier", 1.7f);
            json.addProperty("offset", -0.23f);
            json.addProperty("smooth", smooth);
            var function = DensityFunction.CODEC.parse(lookup.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();
            var sampler = random.getSampler(function);
            float point = expected.get(10421 * 0.13f + 3.5f, 0.0, -8731 * 0.13f - 2.25f) * 1.7f - 0.23f;
            assertEquals(point, sampler.sampleValue(SamplerContext.EMPTY_UNCACHED, 10421, -64, -8731));
            DensityBuffer output = DensityBuffer.createUnpooled(volume.size());
            sampler.sampleVolume(SamplerContext.EMPTY_UNCACHED, output, volume);
            for (int z = 0; z < volume.sizeZ(); z++) {
                for (int x = 0; x < volume.sizeX(); x++) {
                    double sx = smooth ? (volume.blockX(x) + 3.5) * 0.13f : volume.blockX(x) * (double) 0.13f + 3.5;
                    double sz = smooth ? (volume.blockZ(z) - 2.25) * 0.13f : volume.blockZ(z) * (double) 0.13f - 2.25;
                    float sample = expected.get(sx, 0.0, sz) * 1.7f;
                    if (!smooth) sample -= 0.23f;
                    for (int y = 0; y < volume.sizeY(); y++) {
                        assertEquals(sample, output.get(volume.indexUnchecked(x, y, z)));
                    }
                }
            }
        }
    }

    @Test void configNoiseRangeContainsScaledAndOffsetPointAndVolumeValues() {
        var volume = new DensityVolume(3, 2, 2, 10421, -64, -8731, 4, 8, 4);
        var random = RandomState.create(noises, 123456789L, vanillaSettings);
        float[][] transforms = {{-3.5f, 1000.0f}, {2.75f, -1000.0f}, {0.0f, 12.0f}};
        for (float[] transform : transforms) {
            for (boolean smooth : new boolean[]{false, true}) {
                JsonObject json = noiseJson("lbr_terrain:config_noise", "tectonic:parameter/erosion");
                json.addProperty("multiplier", transform[0]);
                json.addProperty("offset", transform[1]);
                json.addProperty("smooth", smooth);
                var function = (NativeTectonicFunctions.ConfigNoiseFunction) DensityFunction.CODEC.parse(
                        lookup.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();
                var range = function.range();
                var sourceRange = function.source().range();
                for (float endpoint : new float[]{sourceRange.min(), sourceRange.max()}) {
                    assertTrue(range.contains(endpoint * transform[0] + transform[1]),
                            "Range must include each transformed source endpoint");
                    if (smooth) {
                        assertTrue(range.contains(endpoint * transform[0]),
                                "Smooth volume range must include values without the point offset");
                    }
                }
                if (!smooth) {
                    assertFalse(range.contains(0.0f), "A large offset must move the declared range");
                }
                var sampler = random.getSampler(function);
                assertTrue(range.contains(sampler.sampleValue(
                        SamplerContext.EMPTY_UNCACHED, 10421, -64, -8731)));
                DensityBuffer output = DensityBuffer.createUnpooled(volume.size());
                sampler.sampleVolume(SamplerContext.EMPTY_UNCACHED, output, volume);
                for (int index = 0; index < volume.size(); index++) {
                    assertTrue(range.contains(output.get(index)), "Range must include volume samples");
                }
            }
        }
    }

    private static Noise referenceNoise(String name, long seed) {
        var key = ResourceKey.create(Registries.NOISE, Identifier.parse(name));
        var random = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(seed).forkPositional();
        return noises.getOrThrow(key).value().create(random.fromHashOf(
                Identifier.withDefaultNamespace(name.substring("tectonic:parameter/".length()))));
    }

    private static DensityFunction density(String type, String name) {
        return DensityFunction.CODEC.parse(lookup.createSerializationContext(JsonOps.INSTANCE), noiseJson(type, name))
                .getOrThrow();
    }

    private static JsonObject noiseJson(String type, String name) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        result.addProperty("noise", name);
        result.addProperty("xz_scale", 0.125);
        result.addProperty("y_scale", 0.0);
        result.addProperty("shift_x", 3.5);
        result.addProperty("shift_y", 0.0);
        result.addProperty("shift_z", -2.25);
        return result;
    }

    private static MaterialRuleContext context() throws Exception {
        var constructor = MaterialRuleContext.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (MaterialRuleContext) constructor.newInstance(null, null,
                new DensityVolume(16, 1, 16, 0, 0, 0), null, null, null, Set.of());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MaterialRuleContext.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
