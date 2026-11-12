package com.lunarboreal.terrain.nms;

import net.minecraft.SharedConstants;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.lang.reflect.Field;
import java.util.function.Supplier;

/** Keeps the upstream lava floor ten blocks above the configured world bottom. */
final class TerrainFluidPicker {
    private TerrainFluidPicker() {}

    static Aquifer.FluidPicker create(NoiseGeneratorSettings settings) {
        int lavaLevel = settings.noiseSettings().minY() + 10;
        int seaLevel = settings.seaLevel();
        var lava = new Aquifer.FluidStatus(lavaLevel, Blocks.LAVA.defaultBlockState());
        var water = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
        var air = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        return (x, y, z) -> SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? air
                : y < Math.min(lavaLevel, seaLevel) ? lava : water;
    }

    static void install(NoiseBasedChunkGenerator generator) throws ReflectiveOperationException {
        Field picker = NoiseBasedChunkGenerator.class.getDeclaredField("globalFluidPicker");
        picker.setAccessible(true);
        Aquifer.FluidPicker value = create(generator.generatorSettings().value());
        picker.set(generator, (Supplier<Aquifer.FluidPicker>) () -> value);
    }
}
