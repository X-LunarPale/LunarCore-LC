package com.lunarboreal.terrainprobe;

import com.google.gson.GsonBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Isolated-server fixture. Never bundled in LunarCore or enabled on a player server. */
public final class TerrainNativeProbe extends JavaPlugin {
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !args[0].matches("[a-z][a-z0-9_-]{0,31}")) return false;
        World world = Bukkit.getWorld(new NamespacedKey("lbr_terrain", args[0]));
        if (world == null) {
            sender.sendMessage("Terrain world is not loaded");
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

    private void save(String name, Map<String, Object> proof) {
        try {
            Path output = Path.of("artifacts").resolve("terrain-native-proof-" + name + ".json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(proof));
            getLogger().info("Terrain proof: " + output.toAbsolutePath());
        } catch (Exception e) {
            getLogger().severe("Cannot save terrain proof: " + e);
        }
    }
}
