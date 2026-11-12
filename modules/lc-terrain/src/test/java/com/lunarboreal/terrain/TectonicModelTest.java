package com.lunarboreal.terrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TectonicModelTest {
    @Test void pinsTheOfficialModArtifactAndDoesNotIncludePackMetadata() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/upstream/tectonic-model.json")) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            assertEquals("5bfb0ad16e4958d844d9d68cd7dd453985b146ca9a5dac4f0620c3e8f424a0c8",
                    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            JsonObject model = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(TectonicModel.UPSTREAM_SHA512, model.getAsJsonObject("upstream").get("sha512").getAsString());
            assertEquals("MIT", model.getAsJsonObject("upstream").get("license").getAsString());
            assertTrue(model.getAsJsonObject("densityFunctions").has("tectonic:__constants/slope_upper"));
            assertFalse(model.has("pack.mcmeta"));
        }
    }

    @Test void keepsTheModsHeightDependentSlopeAndClampForEachWorld() {
        for (int height : new int[]{384, 640, 1024}) {
            TerrainOptions options = new TerrainOptions(height, 1.8, 1.2, true, true, true, true);
            assertEquals(0.0078 * (height - 64 - 80),
                    TectonicModel.densityFunction("tectonic:__constants/max_offset", options).getAsDouble(), 1e-10);
            assertEquals(-0.6942,
                    TectonicModel.densityFunction("tectonic:__constants/min_offset", options).getAsDouble(), 1e-10);
            JsonObject slope = TectonicModel.densityFunction("tectonic:__constants/slope_upper", options).getAsJsonObject();
            JsonObject coordinate = slope.getAsJsonObject("spline").getAsJsonObject("coordinate");
            assertEquals(-(height - 64), coordinate.get("right").getAsInt());
            assertEquals(-30, slope.getAsJsonObject("spline").getAsJsonArray("points").get(0).getAsJsonObject().get("location").getAsInt());
            assertEquals(-10, slope.getAsJsonObject("spline").getAsJsonArray("points").get(1).getAsJsonObject().get("location").getAsInt());
        }
    }

    @Test void mapsAllFeatureSwitchesAndNoiseScalesUsingTheModSemantics() {
        TerrainOptions off = new TerrainOptions(640, 1.8, 2, false, false, false, false);
        TerrainOptions on = TerrainOptions.defaults();
        for (String key : new String[]{"rolling_hills", "lava_tunnels", "jungle_pillars"}) {
            assertEquals(0, TectonicModel.densityFunction("tectonic:__constants/" + key, off).getAsInt());
            assertEquals(1, TectonicModel.densityFunction("tectonic:__constants/" + key, on).getAsInt());
        }
        assertEquals(0, TectonicModel.densityFunction("tectonic:__constants/underground_rivers", off).getAsInt());
        assertEquals(-1, TectonicModel.densityFunction("tectonic:__constants/underground_rivers", on).getAsInt());
        JsonObject noise = TectonicModel.densityFunction("tectonic:__constants/noise/continents", off).getAsJsonObject();
        assertEquals("lc_terrain:config_noise", noise.get("type").getAsString());
        assertEquals("tectonic:parameter/continentalness", noise.get("noise").getAsString());
        assertEquals(0.13f / 2, noise.get("xz_scale").getAsDouble(), 1e-10);
        assertEquals((double) 1.8f, TectonicModel.densityFunction("tectonic:__constants/vertical_scale", on).getAsDouble());
    }

    @Test void appliesIndependentClimateIslandAndContinentNoiseParameters() {
        TerrainOptions options = options(Map.ofEntries(
                Map.entry("continents.continents_scale", 0.2),
                Map.entry("continents.erosion_scale", 0.3),
                Map.entry("continents.ridge_scale", 0.4),
                Map.entry("islands.noise_scale", 0.15),
                Map.entry("islands.noise_multiplier", 1.7),
                Map.entry("islands.noise_offset", -0.25),
                Map.entry("biomes.temperature_scale", 0.06),
                Map.entry("biomes.temperature_multiplier", 1.1),
                Map.entry("biomes.temperature_offset", 0.35),
                Map.entry("biomes.vegetation_scale", 0.08),
                Map.entry("biomes.vegetation_multiplier", 0.4),
                Map.entry("biomes.vegetation_offset", -0.2)));
        assertNoise(options, "continents", 0.2f, 1, 0);
        assertNoise(options, "erosion", 0.3f, 1, 0);
        assertNoise(options, "ridge", 0.4f, 1, 0);
        assertNoise(options, "island_a", 0.15f, 1.7f, -0.25f);
        assertNoise(options, "island_b", 0.15f, 1.7f, -0.25f);
        assertNoise(options, "temperature", 0.06f, 1.1f, 0.35f);
        assertNoise(options, "vegetation", 0.08f, 0.4f, -0.2f);

        JsonObject temperature = noise(options(Map.of(
                "biomes.temperature_scale", 0,
                "biomes.temperature_multiplier", 0,
                "biomes.temperature_offset", 1)), "temperature");
        assertEquals(0, temperature.get("xz_scale").getAsFloat());
        assertEquals(0, temperature.get("multiplier").getAsFloat());
        assertEquals(1, temperature.get("offset").getAsFloat());
    }

    @Test void retainsThePinnedModsExperimentalNoiseAndSpaghettiBehavior() {
        TerrainOptions smooth = options(Map.of("experimental.alternate_erosion_scaling", true));
        for (String key : List.of("continents", "erosion")) {
            assertTrue(noise(smooth, key).get("smooth").getAsBoolean());
            assertFalse(noise(TerrainOptions.defaults(), key).get("smooth").getAsBoolean());
        }
        for (String key : List.of("ridge", "island_a", "island_b", "temperature", "vegetation")) {
            assertFalse(noise(smooth, key).get("smooth").getAsBoolean());
        }
        assertEquals(0, density("cave/spaghetti_enabled", smooth).getAsInt());
        assertTrue(TerrainOptions.schema().stream().noneMatch(field ->
                Set.of("caves.spaghetti_enabled", "experimental.alternate_continents_scaling").contains(field.get("key"))));
    }

    @Test void appliesOceanElevationAndCaveConstantsWithTheModsFloatPrecision() {
        TerrainOptions options = options(Map.ofEntries(
                Map.entry("global_terrain.elevation_boost", 1.6),
                Map.entry("continents.ocean_offset", -0.6),
                Map.entry("continents.flat_terrain_skew", 0.5),
                Map.entry("oceans.ocean_depth", -0.4),
                Map.entry("oceans.deep_ocean_depth", -0.9),
                Map.entry("caves.cheese_enabled", false),
                Map.entry("caves.cheese_additive", -0.2),
                Map.entry("caves.noodle_enabled", false),
                Map.entry("caves.noodle_additive", 0.125),
                Map.entry("caves.depth_cutoff_start", 0.4),
                Map.entry("caves.depth_cutoff_size", 0)));
        assertEquals((double) -0.6f, density("ocean_offset", options).getAsDouble());
        assertEquals((double) 0.5f, density("flat_terrain_skew", options).getAsDouble());
        assertEquals((double) -0.4f, density("ocean_depth", options).getAsDouble());
        assertEquals((double) -0.9f, density("deep_ocean_depth", options).getAsDouble());
        assertEquals(0, density("cave/cheese_enabled", options).getAsInt());
        assertEquals(0, density("cave/noodle_enabled", options).getAsInt());
        assertEquals((double) -0.2f, density("cave/cheese_additive", options).getAsDouble());
        assertEquals((double) 0.125f, density("cave/noodle_additive", options).getAsDouble());
        JsonObject vertical = density("vertical_scale", options).getAsJsonObject();
        assertEquals((double) 1.6f, vertical.getAsJsonObject("left").get("left").getAsDouble());
        assertEquals((double) 1.8f, vertical.get("right").getAsDouble());
        JsonObject cutoff = density("cave/depth_cutoff", options).getAsJsonObject().getAsJsonObject("input");
        assertEquals(1000, cutoff.get("left").getAsDouble(), 1e-10);
        assertEquals((double) -0.4f, cutoff.getAsJsonObject("right").get("right").getAsDouble());
    }

    @Test void disablesIslandsUsingTheOfficialOceanOffsetPredicate() {
        TerrainOptions normal = options(Map.of());
        TerrainOptions disabled = options(Map.of("islands.enabled", false));
        JsonObject limited = TectonicModel.densityFunction("tectonic:noise/raw_continents", disabled).getAsJsonObject();
        assertEquals("minecraft:max", limited.get("type").getAsString());
        assertEquals(-0.49, limited.get("right").getAsDouble());
        assertEquals(TectonicModel.densityFunction("tectonic:noise/raw_continents", normal), limited.get("left"));
        assertEquals(
                TectonicModel.densityFunction("tectonic:noise/raw_continents",
                        options(Map.of("continents.ocean_offset", -0.49))),
                TectonicModel.densityFunction("tectonic:noise/raw_continents",
                        options(Map.of("continents.ocean_offset", -0.49, "islands.enabled", false))));
    }

    @Test void switchesToThePinnedUltrasmoothDensityOverlay() {
        JsonObject normal = TectonicModel.densityFunction("tectonic:sloped_cheese", options(Map.of())).getAsJsonObject();
        JsonObject smooth = TectonicModel.densityFunction("tectonic:sloped_cheese",
                options(Map.of("global_terrain.ultrasmooth", true))).getAsJsonObject();
        assertEquals("minecraft:quarter_negative",
                normal.getAsJsonObject("input").getAsJsonObject("left").getAsJsonObject("right").get("type").getAsString());
        assertEquals("minecraft:mul",
                smooth.getAsJsonObject("input").getAsJsonObject("left").getAsJsonObject("right").get("type").getAsString());
        assertNotEquals(normal, smooth);
    }

    @Test void preservesTheFiveSlopeRangesInsteadOfSubstitutingVanillaSteepness() {
        JsonElement materials = TectonicModel.noiseSettings(TerrainOptions.defaults()).get("material_rule");
        List<JsonObject> slopes = nodesOfType(materials, "lc_terrain:slope");
        assertEquals(5, slopes.size());
        for (JsonObject slope : slopes) {
            assertEquals(4, slope.getAsJsonObject("height_difference").get("min_inclusive").getAsInt());
            assertFalse(slope.getAsJsonObject("height_difference").has("max_inclusive"));
        }
        assertTrue(nodesOfType(materials, "lithostitched:slope").isEmpty());
    }

    @Test void constructsANativeIndependentWorldModelWithVanillaFeatures() {
        for (int height : new int[]{640, 1024}) {
            JsonObject graph = TectonicModel.noiseSettings(new TerrainOptions(height, 1.8, 1.2, true, true, true, true));
            assertEquals(height, graph.getAsJsonObject("noise").get("height").getAsInt());
            assertEquals(-64, graph.getAsJsonObject("noise").get("min_y").getAsInt());
            assertEquals(63, graph.get("sea_level").getAsInt());
            assertTrue(graph.getAsJsonArray("debug_functions").isEmpty());
            assertTrue(graph.get("material_rule").isJsonObject());
            AtomicInteger nodes = new AtomicInteger();
            Set<JsonElement> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            inspect(graph, seen, nodes);
            assertTrue(nodes.get() > 1000);
            assertTrue(nodes.get() < 100_000, "Named functions must share resolved subgraphs");
            assertEquals(32, TectonicModel.noises().size());
        }
    }

    @Test void keepsGenerationGraphsCompatibleDuringResolverOptimizations() throws Exception {
        TerrainOptions[] configurations = {
                TerrainOptions.defaults(),
                new TerrainOptions(1024, 2.8, 1.5, true, true, true, true,
                        Map.of("global_terrain.ultrasmooth", true, "continents.river_ice", true,
                                "experimental.alternate_erosion_scaling", true))
        };
        String[] expected = {
                "19f764a24c788ae9a288f8defeda3db9e7df3349ffa97782b0a190e283e6b6f9",
                "bffe0158b23ecb21239d0bc26efc35270306f4405c72a6122896b0fa12363960"
        };
        for (int index = 0; index < configurations.length; index++) {
            byte[] graph = TectonicModel.noiseSettings(configurations[index]).toString()
                    .replace(TerrainNamespace.CURRENT + ":", TerrainNamespace.LEGACY + ":")
                    .getBytes(StandardCharsets.UTF_8);
            String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(graph));
            assertEquals(expected[index], digest, "Generation graph must match the unoptimized resolver");
        }
    }

    @Test void rejectsMissingAndInvalidNativeResourcesWithTheOriginalError() {
        for (String key : List.of("minecraft:missing_lc_fixture", "minecraft:invalid resource",
                "example:missing", "../minecraft:overworld/depth")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TectonicModel.densityFunction(key, TerrainOptions.defaults()));
            assertEquals("Unknown density function: " + key, error.getMessage());
        }
    }

    @Test void keepsRepeatedNativeResourceLookupsIndependentAcrossBuilds() {
        String key = "minecraft:overworld/depth";
        JsonObject original = TectonicModel.densityFunction(key, TerrainOptions.defaults()).getAsJsonObject();
        JsonObject repeated = TectonicModel.densityFunction(key, TerrainOptions.defaults()).getAsJsonObject();
        assertEquals(original, repeated);
        assertNotSame(original, repeated);
        original.addProperty("fixtureChanged", true);
        assertFalse(repeated.has("fixtureChanged"));
        assertEquals(repeated, TectonicModel.densityFunction(key, TerrainOptions.defaults()));
    }

    @Test void reusesNativeResourcesWithinAResolverWithoutMixingResourceTypes() throws Exception {
        Class<?> type = Class.forName(TectonicModel.class.getName() + "$Resolver");
        var constructor = type.getDeclaredConstructor(TerrainOptions.class);
        constructor.setAccessible(true);
        Object resolver = constructor.newInstance(TerrainOptions.defaults());
        var source = type.getDeclaredMethod("source", String.class, String.class);
        source.setAccessible(true);
        Object depth = source.invoke(resolver, "density_function", "minecraft:overworld/depth");
        assertNotNull(depth);
        assertSame(depth, source.invoke(resolver, "density_function", "minecraft:overworld/depth"));
        assertNull(source.invoke(resolver, "density_function", "minecraft:overworld"));
        Object material = source.invoke(resolver, "material_rule", "minecraft:overworld");
        assertNotNull(material);
        assertSame(material, source.invoke(resolver, "material_rule", "minecraft:overworld"));
        assertNull(source.invoke(resolver, "density_function", "minecraft:overworld"));
    }

    private void inspect(JsonElement value, Set<JsonElement> seen, AtomicInteger count) {
        if (!seen.add(value)) return;
        count.incrementAndGet();
        if (value.isJsonArray()) value.getAsJsonArray().forEach(child -> inspect(child, seen, count));
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (object.has("type")) {
            String type = object.get("type").getAsString();
            assertTrue(type.startsWith("minecraft:") || type.startsWith("paper:")
                    || Set.of("lc_terrain:slope", "lc_terrain:seeded_noise", "lc_terrain:config_noise").contains(type), type);
        }
        if (object.has("noise") && object.get("noise").isJsonPrimitive()
                && object.get("noise").getAsString().startsWith("tectonic:")) {
            assertTrue(TectonicModel.noises().containsKey(object.get("noise").getAsString()));
        }
        object.entrySet().forEach(entry -> {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()
                    && !entry.getKey().equals("noise")) {
                assertFalse(entry.getValue().getAsString().startsWith("tectonic:"), entry.toString());
            }
            inspect(entry.getValue(), seen, count);
        });
    }

    private static TerrainOptions options(Map<String, Object> advanced) {
        return new TerrainOptions(832, 1.8, 2, true, true, true, true, advanced);
    }

    private static JsonElement density(String suffix, TerrainOptions options) {
        return TectonicModel.densityFunction("tectonic:__constants/" + suffix, options);
    }

    private static JsonObject noise(TerrainOptions options, String key) {
        JsonObject result = density("noise/" + key, options).getAsJsonObject();
        return "minecraft:cache".equals(result.get("type").getAsString()) ? result.getAsJsonObject("input") : result;
    }

    private static void assertNoise(TerrainOptions options, String key, float scale, float multiplier, float offset) {
        JsonObject result = noise(options, key);
        assertEquals("lc_terrain:config_noise", result.get("type").getAsString());
        assertEquals(scale / (float) options.horizontalScale(), result.get("xz_scale").getAsFloat());
        assertEquals(multiplier, result.get("multiplier").getAsFloat());
        assertEquals(offset, result.get("offset").getAsFloat());
    }

    private static List<JsonObject> nodesOfType(JsonElement root, String type) {
        List<JsonObject> result = new ArrayList<>();
        Set<JsonElement> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collectNodes(root, type, seen, result);
        return result;
    }

    private static void collectNodes(JsonElement value, String type, Set<JsonElement> seen, List<JsonObject> result) {
        if (!seen.add(value)) return;
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(child -> collectNodes(child, type, seen, result));
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("type") && type.equals(object.get("type").getAsString())) result.add(object);
            object.entrySet().forEach(entry -> collectNodes(entry.getValue(), type, seen, result));
        }
    }
}
