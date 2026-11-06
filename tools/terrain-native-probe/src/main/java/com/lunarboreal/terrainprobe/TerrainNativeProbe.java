package com.lunarboreal.terrainprobe;

import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Isolated-server fixture. Never bundled in LunarCore or enabled on a player server. */
public final class TerrainNativeProbe extends JavaPlugin {
    private static final String MARKER = "tectonic:blending_version";
    private final Set<String> hookProbes = new HashSet<>();

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || args.length > 2 || !args[0].matches("[a-z][a-z0-9_-]{0,31}")
                || args.length == 2 && !args[1].equalsIgnoreCase("hooks")) return false;
        World world = Bukkit.getWorld(new NamespacedKey("lbr_terrain", args[0]));
        if (world == null) {
            sender.sendMessage("Terrain world is not loaded");
            return true;
        }
        if (args.length == 2) {
            if (!hookProbes.add(args[0])) {
                sender.sendMessage("A hooks probe is already running for this world");
                return true;
            }
            new HooksProbe(sender, args[0], world).start();
            return true;
        }
        ServerLevel level = ((CraftWorld) world).getHandle();
        var generator = level.getChunkSource().getGenerator();
        var randomState = level.getChunkSource().randomState();
        Random positions = new Random(739163);
        new BukkitRunnable() {
            int count;
            int highest = Integer.MIN_VALUE;
            int bestX;
            int bestZ;
            @Override public void run() {
                try {
                    for (int i = 0; i < 2 && count < 256; i++, count++) {
                        int x = positions.nextInt(65536) - 32768;
                        int z = positions.nextInt(65536) - 32768;
                        int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                        if (y > highest) {
                            highest = y;
                            bestX = x;
                            bestZ = z;
                        }
                    }
                    if (count < 256) return;
                    cancel();
                    int x = bestX, z = bestZ, predicted = highest;
                    world.getChunkAtAsync(x >> 4, z >> 4, true, true).whenComplete((chunk, failure) ->
                            Bukkit.getScheduler().runTask(TerrainNativeProbe.this, () -> {
                                Map<String, Object> proof = new LinkedHashMap<>();
                                proof.put("world", world.getName());
                                proof.put("key", world.getKey().asString());
                                proof.put("generator", generator.getClass().getName());
                                proof.put("seed", Long.toString(world.getSeed()));
                                proof.put("minY", world.getMinHeight());
                                proof.put("maxY", world.getMaxHeight());
                                proof.put("samples", count);
                                proof.put("highestPredictedY", predicted);
                                proof.put("x", x);
                                proof.put("z", z);
                                proof.put("primaryMaxY", Bukkit.getWorlds().getFirst().getMaxHeight());
                                if (failure == null) {
                                    int actualY = world.getHighestBlockYAt(x, z);
                                    proof.put("actualSurfaceY", actualY);
                                    proof.put("surfaceMaterial", world.getBlockAt(x, actualY, z).getType().name());
                                    proof.put("biome", world.getBiome(x, actualY, z).getKey().asString());
                                } else {
                                    proof.put("error", failure.toString());
                                }
                                save(world.getName(), proof);
                            }));
                } catch (Throwable failure) {
                    cancel();
                    save(world.getName(), Map.of("error", failure.toString()));
                }
            }
        }.runTaskTimer(this, 1, 1);
        sender.sendMessage("Native terrain sampling queued");
        return true;
    }

    private final class HooksProbe {
        private final CommandSender sender;
        private final String name;
        private final World initialWorld;
        private final Map<String, Object> proof = new LinkedHashMap<>();
        private Object module;
        private World vanilla;
        private Object vanillaGenerator;
        private Object vanillaSettings;
        private Map<String, Object> vanillaBefore;
        private Map<String, Object> terrainBefore;
        private List<Map<String, Object>> vanillaTemperatures;
        private List<Map<String, Object>> temperaturesBefore;
        private int snowOffset;
        private int chunkX;
        private int chunkZ;
        private boolean finished;

        private HooksProbe(CommandSender sender, String name, World world) {
            this.sender = sender;
            this.name = name;
            initialWorld = world;
            proof.put("world", world.getKey().asString());
        }

        private void start() {
            try {
                require(initialWorld.getPlayers().isEmpty(), "Run the hooks probe without players in the test world");
                vanilla = Bukkit.getWorlds().stream()
                        .filter(world -> world.getEnvironment() == World.Environment.NORMAL
                                && !world.getKey().getNamespace().equals("lbr_terrain"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("No vanilla overworld is loaded"));
                module = terrainModule();
                Object options = invoke(module, "optionsFor", new Class<?>[]{World.class}, initialWorld);
                require(options != null, "The terrain world has no saved options");
                snowOffset = ((Number) invoke(options, "number",
                        new Class<?>[]{String.class}, "general.snow_start_offset")).intValue();
                chunkX = initialWorld.getSpawnLocation().getBlockX() >> 4;
                chunkZ = initialWorld.getSpawnLocation().getBlockZ() >> 4;
                vanillaGenerator = nativeLevel(vanilla).getChunkSource().getGenerator();
                vanillaSettings = ((NoiseBasedChunkGenerator) vanillaGenerator).generatorSettings().value();
                vanillaBefore = settings(vanilla);
                terrainBefore = settings(initialWorld);
                proof.put("snowOffset", snowOffset);
                proof.put("vanillaWorld", vanilla.getKey().asString());
                proof.put("terrainSettings", terrainBefore);
                proof.put("vanillaSettings", vanillaBefore);
                int vanillaX = vanilla.getSpawnLocation().getBlockX() >> 4;
                int vanillaZ = vanilla.getSpawnLocation().getBlockZ() >> 4;
                CompletableFuture.allOf(
                        initialWorld.getChunkAtAsync(chunkX, chunkZ, true, true),
                        vanilla.getChunkAtAsync(vanillaX, vanillaZ, true, true))
                        .whenComplete((ignored, failure) -> sync(() -> {
                            if (failure != null) { finish(failure); return; }
                            beforeReload();
                        }));
                sender.sendMessage("Native hooks probe queued; the test world will be unloaded and reloaded");
            } catch (Throwable failure) {
                finish(failure);
            }
        }

        private void beforeReload() {
            try {
                ServerLevel level = nativeLevel(initialWorld);
                require(((NoiseBasedChunkGenerator) level.getChunkSource().getGenerator())
                        .generatorSettings().value() != vanillaSettings, "Terrain and vanilla share noise settings");
                temperaturesBefore = temperatures(initialWorld, chunkX, chunkZ, snowOffset);
                proof.put("temperatureBeforeReload", temperaturesBefore);
                proof.put("terrainSerialization", serialization(initialWorld, chunkX, chunkZ, true));
                proof.put("vanillaSerialization", serialization(vanilla,
                        vanilla.getSpawnLocation().getBlockX() >> 4,
                        vanilla.getSpawnLocation().getBlockZ() >> 4, false));
                checkVanilla();
                invoke(module, "unload", new Class<?>[]{String.class, String.class}, name, "native-hooks-probe");
                require(Bukkit.getWorld(initialWorld.getKey()) == null, "Terrain world was not unloaded");
                proof.put("unloaded", true);
                invoke(module, "queueLoad", new Class<?>[]{String.class, String.class}, name, "native-hooks-probe");
                new BukkitRunnable() {
                    int ticks;
                    @Override public void run() {
                        World reloaded = Bukkit.getWorld(initialWorld.getKey());
                        if (reloaded == null) {
                            if (++ticks < 400) return;
                            cancel();
                            finish(new IllegalStateException("Terrain world did not reload within 400 ticks"));
                            return;
                        }
                        cancel();
                        reloaded.getChunkAtAsync(chunkX, chunkZ, true, true).whenComplete((chunk, failure) ->
                                sync(() -> {
                                    if (failure != null) { finish(failure); return; }
                                    afterReload(reloaded);
                                }));
                    }
                }.runTaskTimer(TerrainNativeProbe.this, 1, 1);
            } catch (Throwable failure) {
                finish(failure);
            }
        }

        private void afterReload(World reloaded) {
            try {
                require(nativeLevel(reloaded) != nativeLevel(initialWorld), "Reload reused the previous ServerLevel");
                require(terrainBefore.equals(settings(reloaded)), "Terrain generation settings changed after reload");
                var temperatures = temperatures(reloaded, chunkX, chunkZ, snowOffset);
                proof.put("temperatureAfterReload", temperatures);
                require(temperaturesBefore.equals(temperatures), "Terrain temperature changed after reload");
                proof.put("serializationAfterReload", serialization(reloaded, chunkX, chunkZ, true));
                checkVanilla();
                proof.put("reloaded", true);
                finish(null);
            } catch (Throwable failure) {
                finish(failure);
            }
        }

        private void checkVanilla() {
            ServerLevel level = nativeLevel(vanilla);
            require(level.getChunkSource().getGenerator() == vanillaGenerator, "Vanilla generator was replaced");
            require(((NoiseBasedChunkGenerator) vanillaGenerator).generatorSettings().value() == vanillaSettings,
                    "Vanilla noise settings were replaced");
            require(vanillaBefore.equals(settings(vanilla)), "Vanilla generation settings changed");
            var temperatures = temperatures(vanilla,
                    vanilla.getSpawnLocation().getBlockX() >> 4,
                    vanilla.getSpawnLocation().getBlockZ() >> 4, 0);
            if (vanillaTemperatures == null) vanillaTemperatures = temperatures;
            else require(vanillaTemperatures.equals(temperatures), "Vanilla biome temperatures changed");
            proof.put("vanillaTemperature", temperatures);
            proof.put("vanillaUnchanged", true);
        }

        private void finish(Throwable failure) {
            if (finished) return;
            finished = true;
            hookProbes.remove(name);
            proof.put("passed", failure == null);
            if (failure != null) {
                while ((failure instanceof InvocationTargetException || failure instanceof CompletionException)
                        && failure.getCause() != null) failure = failure.getCause();
                proof.put("error", failure.toString());
            }
            String filename = "terrain-native-hooks-" + name + ".json";
            saveArtifact(filename, proof);
            sender.sendMessage((failure == null ? "Hooks probe passed: " : "Hooks probe failed: ") + "artifacts/" + filename);
        }
    }

    private static ServerLevel nativeLevel(World world) {
        return ((CraftWorld) world).getHandle();
    }

    private static Map<String, Object> settings(World world) {
        ServerLevel level = nativeLevel(world);
        require(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                "Expected a native noise generator in " + world.getKey());
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var settings = generator.generatorSettings().value();
        return Map.of("seed", Long.toString(world.getSeed()), "minY", world.getMinHeight(),
                "maxY", world.getMaxHeight(), "noiseMinY", settings.noiseSettings().minY(),
                "noiseHeight", settings.noiseSettings().height(), "seaLevel", settings.seaLevel(),
                "dimensionType", level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier().toString(),
                "generator", generator.getClass().getName());
    }

    private static List<Map<String, Object>> temperatures(World world, int chunkX, int chunkZ, int offset) {
        ServerLevel level = nativeLevel(world);
        var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int y : new int[]{world.getMaxHeight() - 8,
                world.getMinHeight() + (world.getMaxHeight() - world.getMinHeight()) / 2}) {
            for (int delta : new int[]{6, 10}) {
                BlockPos position = new BlockPos((chunkX << 4) + delta, y, (chunkZ << 4) + delta);
                var queried = level.getBiomeManager().getBiome(position);
                var key = queried.unwrapKey().orElseThrow();
                var original = registry.getOrThrow(key);
                float actual = queried.value().getTemperature(position, level.getSeaLevel());
                float expected = original.value().getTemperature(position.below(offset), level.getSeaLevel());
                float unadjusted = original.value().getTemperature(position, level.getSeaLevel());
                require(Math.abs(actual - expected) < 0.000001F,
                        "Biome manager returned an incorrect temperature in " + world.getKey() + " at " + position);
                require(offset == 0 ? queried.value() == original.value() : queried.value() != original.value(),
                        "Biome manager temperature isolation failed in " + world.getKey());
                samples.add(Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ(),
                        "biome", key.identifier().toString(), "actual", actual, "expected", expected,
                        "unadjusted", unadjusted, "queryCopy", queried.value() != original.value()));
            }
        }
        return samples;
    }

    private static Map<String, Object> serialization(World world, int chunkX, int chunkZ, boolean managed) {
        ServerLevel level = nativeLevel(world);
        var copied = SerializableChunkData.copyOf(level, level.getChunk(chunkX, chunkZ));
        CompoundTag saved = copied.write();
        require(managed ? saved.getIntOr(MARKER, 0) == 1 : !saved.contains(MARKER),
                "Chunk copy/write marker isolation failed in " + world.getKey());
        CompoundTag legacy = saved.copy();
        legacy.remove(MARKER);
        legacy.remove("blending_data");
        legacy.put("Heightmaps", new CompoundTag());
        legacy.putBoolean("isLightOn", true);
        var parsed = SerializableChunkData.parse(level, copied.containerFactory(), legacy);
        require(parsed != null, "Native chunk parser rejected the test chunk");
        require(managed ? parsed.blendingData() != null && !legacy.contains("Heightmaps")
                        && !legacy.contains("isLightOn")
                        : parsed.blendingData() == null && legacy.contains("Heightmaps")
                        && legacy.getBooleanOr("isLightOn", false),
                "Native chunk parse compatibility isolation failed in " + world.getKey());
        return Map.of("x", chunkX, "z", chunkZ, "marker", saved.getIntOr(MARKER, 0),
                "legacyParsed", true, "legacyBlending", parsed.blendingData() != null,
                "legacyHeightmapsRetained", legacy.contains("Heightmaps"),
                "legacyLightRetained", legacy.contains("isLightOn"));
    }

    private Object terrainModule() throws Exception {
        var plugin = Bukkit.getPluginManager().getPlugin("LunarCore");
        require(plugin != null, "LunarCore is unavailable");
        ClassLoader loader = plugin.getClass().getClassLoader();
        Object api = Class.forName("com.lunarboreal.api.LC", true, loader).getMethod("get").invoke(null);
        Object manager = Class.forName("com.lunarboreal.api.LunarBorealAPI", true, loader)
                .getMethod("modules").invoke(api);
        return ((Optional<?>) Class.forName("com.lunarboreal.api.module.ModuleManager", true, loader)
                .getMethod("get", String.class).invoke(manager, "terrain")).orElseThrow();
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void sync(Runnable action) {
        Bukkit.getScheduler().runTask(this, action);
    }

    private void save(String name, Map<String, Object> proof) {
        saveArtifact("terrain-native-proof-" + name + ".json", proof);
    }

    private void saveArtifact(String filename, Map<String, Object> proof) {
        try {
            Path output = Path.of("artifacts").resolve(filename);
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(proof));
            getLogger().info("Terrain proof: " + output.toAbsolutePath());
        } catch (Exception e) {
            getLogger().severe("Cannot save terrain proof: " + e);
        }
    }
}
