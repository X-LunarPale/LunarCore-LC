package com.lunarboreal.terrain.nms;

import com.lunarboreal.terrain.TectonicModel;
import com.lunarboreal.terrain.TerrainOptions;
import com.lunarboreal.terrain.TerrainTargets;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** A command-owned sampler context; queries never load or generate chunks. */
public final class TerrainQuery {
    public record Position(int x, int z) {}
    public record Debug(String region, double erosion, double selector, double depthCutoff) {}

    private final Map<String, DensitySampler> samplers = new LinkedHashMap<>();
    private final SamplerContext context = SamplerContext.builder().enableCaches().build();

    public TerrainQuery(HolderLookup.Provider registries, RandomState random, TerrainOptions options,
                        List<String> functions) {
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        for (String function : functions) {
            samplers.put(function, random.getSampler(DensityFunction.CODEC.parse(ops,
                    TectonicModel.densityFunction("tectonic:" + function, options)).getOrThrow()));
        }
    }

    public static TerrainQuery locator(HolderLookup.Provider registries, RandomState random,
                                       TerrainOptions options, TerrainTargets.Target target) {
        return new TerrainQuery(registries, random, options,
                target.conditions().stream().map(TerrainTargets.Bound::key).distinct().toList());
    }

    public static TerrainQuery debugger(HolderLookup.Provider registries, RandomState random, TerrainOptions options) {
        return new TerrainQuery(registries, random, options, List.of("noise/continent/erosion",
                "noise/region_selector", "__constants/cave/depth_cutoff"));
    }

    public Optional<Position> locate(TerrainTargets.Target target, int originX, int originZ,
                                     BooleanSupplier cancelled) {
        for (BlockPos offset : BlockPos.spiralAround(BlockPos.ZERO, 400, Direction.EAST, Direction.SOUTH)) {
            if (cancelled.getAsBoolean()) return Optional.empty();
            long x = (long) originX + offset.getX() * 64L;
            long z = (long) originZ + offset.getZ() * 64L;
            if (Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) continue;
            if (target.matches(key -> sample(key, (int) x, 0, (int) z))) {
                return Optional.of(new Position((int) x, (int) z));
            }
        }
        return Optional.empty();
    }

    public Debug debug(int x, int y, int z) {
        double erosion = rounded(sample("noise/continent/erosion", x, y, z));
        double selector = rounded(sample("noise/region_selector", x, y, z));
        String region = erosion < 0 ? selector < 0 ? "Club" : "Heart"
                : selector < 0 ? "Spade" : "Diamond";
        return new Debug(region, erosion, selector, rounded(sample("__constants/cave/depth_cutoff", x, y, z)));
    }

    private double sample(String function, int x, int y, int z) {
        return samplers.get(function).sampleValue(context, x, y, z);
    }

    private static double rounded(double sample) {
        return Math.round(sample * 1000) / 1000d;
    }
}
