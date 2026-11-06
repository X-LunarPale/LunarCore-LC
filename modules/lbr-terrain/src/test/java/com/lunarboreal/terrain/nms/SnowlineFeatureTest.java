package com.lunarboreal.terrain.nms;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowlineFeatureTest {
    @Test void officialOverkillOffsetSuppressesVanillaSnowBelowItsSnowline() {
        Biome biome = new Biome.BiomeBuilder()
                .hasPrecipitation(true).temperature(0.6F).downfall(0.5F)
                .specialEffects(new BiomeSpecialEffects.Builder().waterColor(0).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY)
                .generationSettings(BiomeGenerationSettings.EMPTY).build();
        BlockPos mountain = new BlockPos(0, 700, 0);
        assertTrue(SnowlineFeature.coldAtSnowline(biome, mountain, 63, 0));
        assertFalse(SnowlineFeature.coldAtSnowline(biome, mountain, 63, 512));
    }
}
