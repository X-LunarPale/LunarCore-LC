package com.lunarboreal.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Adapts Apollo's Tectonic 3.0.29 mod model to native 26.3 functions.
 * Upstream attribution and reproducible extraction are in upstream/TECTONIC-NOTICE.txt.
 */
public final class TectonicModel {
    public static final String VERSION = "3.0.29";
    public static final String UPSTREAM_SHA512 =
            "3fa046943f05d58b5dfc486d8dd7a073085b81aec5c96692e7626b87cb64ab295b5e6a3214f6ccc28746b1eddbcc16360d1c7adee9f97ad29126ac7daca0a257";
    private static final String RESOURCE = "/upstream/tectonic-model.json";
    private static final JsonObject MODEL = load();
    private static final JsonObject DENSITY_OVERLAYS = loadDensityOverlays();
    private static final Pattern VANILLA_RESOURCE = Pattern.compile("minecraft:[a-z0-9_/.-]+");
    private static final Set<String> DENSITY_LITERAL_FIELDS = Set.of("type", "noise", "Name", "random_name", "label");
    private static final Set<String> MATERIAL_LITERAL_FIELDS = Set.of("type", "noise", "Name", "random_name");
    private static final Set<String> MATERIAL_DENSITY_FIELDS = Set.of("density_function", "density", "filler_gap", "richness");

    private TectonicModel() {}

    public static JsonObject noiseSettings(TerrainOptions options) {
        Objects.requireNonNull(options, "options");
        Resolver resolver = new Resolver(options);
        JsonObject source = MODEL.getAsJsonObject("noiseSettings");
        JsonObject result = source.deepCopy();
        result.getAsJsonObject("noise").addProperty("height", options.height());
        result.add("aquifers", resolver.expandDensity(source.get("aquifers")));
        // Debug overlays duplicate several megabytes of the generation tree.
        result.add("debug_functions", new JsonArray());
        result.add("noise_router", resolver.expandDensity(source.get("noise_router")));
        result.add("material_rule", resolver.expandMaterial(source.get("material_rule")));
        return result;
    }

    public static JsonObject build(TerrainOptions options) {
        return noiseSettings(options);
    }

    /** Named noises must retain holder keys for RandomState's per-noise seed derivation. */
    public static Map<String, JsonObject> noises() {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        MODEL.getAsJsonObject("noises").entrySet().forEach(entry ->
                result.put(entry.getKey(), entry.getValue().getAsJsonObject().deepCopy()));
        return result;
    }

    /** Exposes an individual native function for codec/sampling verification. */
    public static JsonElement densityFunction(String key, TerrainOptions options) {
        Resolver resolver = new Resolver(Objects.requireNonNull(options, "options"));
        if (resolver.source("density_function", key) == null) throw new IllegalArgumentException("Unknown density function: " + key);
        return resolver.density(key);
    }

    private static JsonObject load() {
        try (InputStream input = TectonicModel.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing pinned Tectonic model");
            JsonObject model = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject upstream = model.getAsJsonObject("upstream");
            if (!VERSION.equals(upstream.get("version").getAsString())
                    || !UPSTREAM_SHA512.equals(upstream.get("sha512").getAsString())
                    || !"26.3".equals(upstream.get("gameVersion").getAsString())) {
                throw new IOException("Unexpected upstream Tectonic model");
            }
            return model;
        } catch (IOException | RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static JsonObject loadDensityOverlays() {
        try (InputStream input = TectonicModel.class.getResourceAsStream("/upstream/tectonic-features.json")) {
            if (input == null) throw new IOException("Missing pinned Tectonic feature model");
            JsonObject model = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!UPSTREAM_SHA512.equals(model.get("sha512").getAsString())) {
                throw new IOException("Unexpected upstream Tectonic feature model");
            }
            return model.getAsJsonObject("densityOverlays");
        } catch (IOException | RuntimeException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final class Resolver {
        private final TerrainOptions options;
        private final JsonObject densities = MODEL.getAsJsonObject("densityFunctions");
        private final JsonObject materials = MODEL.getAsJsonObject("materialRules");
        private final Map<String, JsonElement> resolvedDensities = new HashMap<>();
        private final Map<String, JsonElement> resolvedMaterials = new HashMap<>();
        private final Map<String, JsonElement> vanillaDensities = new HashMap<>();
        private final Map<String, JsonElement> vanillaMaterials = new HashMap<>();
        private final Set<String> resolving = new HashSet<>();

        private Resolver(TerrainOptions options) {
            this.options = options;
        }

        private JsonElement density(String key) {
            JsonElement previous = resolvedDensities.get(key);
            if (previous != null) return previous;
            if (!resolving.add("density:" + key)) throw new IllegalStateException("Cyclic density function: " + key);
            JsonElement value;
            if ("minecraft:overworld/temperature".equals(key)) {
                value = density("tectonic:__constants/noise/temperature");
            } else if ("minecraft:overworld/vegetation".equals(key)) {
                value = density("tectonic:__constants/noise/vegetation");
            } else {
                value = expandDensity(source("density_function", key));
            }
            if ("tectonic:noise/raw_continents".equals(key) && !options.flag("islands.enabled")
                    && (float) options.number("continents.ocean_offset") < -0.49f) {
                value = binary("max", value, new JsonPrimitive(-0.49));
            }
            resolving.remove("density:" + key);
            resolvedDensities.put(key, value);
            return value;
        }

        private JsonElement expandDensity(JsonElement value) {
            if (value == null || value.isJsonNull()) throw new IllegalStateException("Missing model density");
            if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isString()
                        && source("density_function", value.getAsString()) != null) return density(value.getAsString());
                return value;
            }
            if (value.isJsonArray()) {
                JsonArray result = new JsonArray();
                for (JsonElement child : value.getAsJsonArray()) result.add(expandDensity(child));
                return result;
            }
            JsonObject object = value.getAsJsonObject();
            String type = object.has("type") ? object.get("type").getAsString() : "";
            if ("tectonic:config_constant".equals(type)) return constant(object.get("key").getAsString());
            if ("tectonic:config_noise".equals(type)) return configNoise(object);
            if ("tectonic:config_clamp".equals(type)) {
                return binary("min", binary("max", expandDensity(object.get("input")),
                        expandDensity(object.get("min"))), expandDensity(object.get("max")));
            }
            if (type.startsWith("tectonic:")) throw new IllegalStateException("Unadapted mod function: " + type);
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (DENSITY_LITERAL_FIELDS.contains(entry.getKey())) {
                    result.add(entry.getKey(), entry.getValue().deepCopy());
                } else {
                    result.add(entry.getKey(), expandDensity(entry.getValue()));
                }
            }
            if ("minecraft:noise".equals(type) && result.has("noise") && result.get("noise").isJsonPrimitive()
                    && result.get("noise").getAsJsonPrimitive().isString()
                    && result.get("noise").getAsString().startsWith("tectonic:parameter/")) {
                result.addProperty("type", "lbr_terrain:seeded_noise");
            }
            return fold(result, type);
        }

        private JsonElement expandMaterial(JsonElement value) {
            if (value.isJsonPrimitive()) {
                if (!value.getAsJsonPrimitive().isString()
                        || source("material_rule", value.getAsString()) == null) return value;
                String key = value.getAsString();
                JsonElement previous = resolvedMaterials.get(key);
                if (previous != null) return previous;
                if (!resolving.add("material:" + key)) throw new IllegalStateException("Cyclic material rule: " + key);
                JsonElement result = expandMaterial(source("material_rule", key));
                resolving.remove("material:" + key);
                resolvedMaterials.put(key, result);
                return result;
            }
            if (value.isJsonArray()) {
                JsonArray result = new JsonArray();
                for (JsonElement child : value.getAsJsonArray()) result.add(expandMaterial(child));
                return result;
            }
            if (value.getAsJsonObject().has("type")
                    && "lithostitched:slope".equals(value.getAsJsonObject().get("type").getAsString())) {
                JsonObject slope = value.getAsJsonObject().deepCopy();
                slope.addProperty("type", "lbr_terrain:slope");
                return slope;
            }
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (MATERIAL_LITERAL_FIELDS.contains(entry.getKey())) {
                    result.add(entry.getKey(), entry.getValue().deepCopy());
                } else if (MATERIAL_DENSITY_FIELDS.contains(entry.getKey())) {
                    result.add(entry.getKey(), expandDensity(entry.getValue()));
                } else {
                    result.add(entry.getKey(), expandMaterial(entry.getValue()));
                }
            }
            return result;
        }

        private JsonElement source(String kind, String key) {
            if ("density_function".equals(kind) && "tectonic:sloped_cheese".equals(key)
                    && options.flag("global_terrain.ultrasmooth")) {
                JsonElement overlay = DENSITY_OVERLAYS.get(key);
                if (overlay == null) throw new IllegalStateException("Missing upstream ultrasmooth density overlay");
                return overlay;
            }
            boolean density = kind.equals("density_function");
            JsonObject owned = density ? densities : materials;
            if (owned.has(key)) return owned.get(key);
            Map<String, JsonElement> resources = density ? vanillaDensities : vanillaMaterials;
            if (resources.containsKey(key)) return resources.get(key);
            if (!VANILLA_RESOURCE.matcher(key).matches()) {
                resources.put(key, null);
                return null;
            }
            String resource = "/data/minecraft/worldgen/" + kind + "/" + key.substring(10) + ".json";
            try (InputStream input = TectonicModel.class.getResourceAsStream(resource)) {
                JsonElement result = input == null ? null :
                        JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                resources.put(key, result);
                return result;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read native 26.3 generator resource " + resource, e);
            }
        }

        private JsonElement constant(String key) {
            double number = switch (key) {
                case "vertical_scale" -> options.verticalScale();
                case "elevation_boost" -> options.number("global_terrain.elevation_boost");
                case "min_y" -> -64;
                case "max_y" -> options.height() - 64;
                case "lava_tunnels" -> options.lavaTunnels() ? 1 : 0;
                case "underground_rivers" -> options.undergroundRivers() ? -1 : 0;
                case "rolling_hills" -> options.rollingHills() ? 1 : 0;
                case "jungle_pillars" -> options.junglePillars() ? 1 : 0;
                case "ocean_offset" -> options.number("continents.ocean_offset");
                case "alternate_erosion_scale" -> (float) options.number("continents.erosion_scale") * 4f;
                case "flat_terrain_skew" -> options.number("continents.flat_terrain_skew");
                case "ocean_depth" -> options.number("oceans.ocean_depth");
                case "deep_ocean_depth" -> options.number("oceans.deep_ocean_depth");
                case "depth_cutoff_start" -> options.number("caves.depth_cutoff_start");
                case "depth_cutoff_size" -> options.number("caves.depth_cutoff_size");
                case "cheese_enabled" -> options.flag("caves.cheese_enabled") ? 1 : 0;
                case "noodle_enabled" -> options.flag("caves.noodle_enabled") ? 1 : 0;
                // The pinned mod's ConfigState#getValue omits this key and returns zero.
                case "spaghetti_enabled" -> 0;
                case "cheese_additive" -> options.number("caves.cheese_additive");
                case "noodle_additive" -> options.number("caves.noodle_additive");
                default -> throw new IllegalStateException("Unknown upstream configuration key: " + key);
            };
            return new JsonPrimitive((double) (float) number);
        }

        private JsonElement configNoise(JsonObject source) {
            String key = source.get("key").getAsString();
            String scaleKey = switch (key) {
                case "continents" -> "continents.continents_scale";
                case "erosion" -> "continents.erosion_scale";
                case "ridge" -> "continents.ridge_scale";
                case "island" -> "islands.noise_scale";
                case "temperature" -> "biomes.temperature_scale";
                case "vegetation" -> "biomes.vegetation_scale";
                default -> throw new IllegalStateException("Unknown upstream noise key");
            };
            String parameters = switch (key) {
                case "island" -> "islands.noise_";
                case "temperature", "vegetation" -> "biomes." + key + "_";
                default -> null;
            };
            JsonObject result = new JsonObject();
            result.addProperty("type", "lbr_terrain:config_noise");
            result.add("noise", source.get("noise"));
            result.addProperty("xz_scale", (float) options.number(scaleKey) / (float) options.horizontalScale());
            result.addProperty("y_scale", 0);
            result.add("shift_x", expandDensity(source.get("shift_x")));
            result.addProperty("shift_y", 0);
            result.add("shift_z", expandDensity(source.get("shift_z")));
            result.addProperty("multiplier", parameters == null ? 1f : (float) options.number(parameters + "multiplier"));
            result.addProperty("offset", parameters == null ? 0f : (float) options.number(parameters + "offset"));
            // Both continent and erosion states read this same flag in the pinned artifact.
            result.addProperty("smooth", ("continents".equals(key) || "erosion".equals(key))
                    && options.flag("experimental.alternate_erosion_scaling"));
            return result;
        }
    }

    private static JsonElement binary(String operation, JsonElement left, JsonElement right) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:" + operation);
        result.add("left", left);
        result.add("right", right);
        return fold(result, "minecraft:" + operation);
    }

    private static JsonElement fold(JsonObject value, String type) {
        JsonElement left = value.get("left");
        JsonElement right = value.get("right");
        if (numeric(left) && numeric(right)) {
            double a = left.getAsDouble();
            double b = right.getAsDouble();
            Double result = switch (type) {
                case "minecraft:add" -> a + b;
                case "minecraft:sub" -> a - b;
                case "minecraft:mul" -> a * b;
                case "minecraft:min" -> Math.min(a, b);
                case "minecraft:max" -> Math.max(a, b);
                default -> null;
            };
            if (result != null) return new JsonPrimitive(result);
        }
        if ("minecraft:mul".equals(type) && (zero(left) || zero(right))) return new JsonPrimitive(0);
        if ("minecraft:mul".equals(type) && one(left)) return right;
        if ("minecraft:mul".equals(type) && one(right)) return left;
        if ("minecraft:add".equals(type) && zero(left)) return right;
        if ("minecraft:add".equals(type) && zero(right)) return left;
        JsonElement input = value.get("input");
        if (numeric(input)) {
            double a = input.getAsDouble();
            Double result = switch (type) {
                case "minecraft:cache" -> a;
                case "minecraft:negate" -> -a;
                case "minecraft:square" -> a * a;
                case "minecraft:reciprocal" -> 1 / a;
                case "minecraft:clamp" -> Math.clamp(a, value.get("min").getAsDouble(), value.get("max").getAsDouble());
                default -> null;
            };
            if (result != null && Double.isFinite(result)) return new JsonPrimitive(result);
        }
        return value;
    }

    private static boolean numeric(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    private static boolean zero(JsonElement value) {
        return numeric(value) && value.getAsDouble() == 0;
    }

    private static boolean one(JsonElement value) {
        return numeric(value) && value.getAsDouble() == 1;
    }
}
