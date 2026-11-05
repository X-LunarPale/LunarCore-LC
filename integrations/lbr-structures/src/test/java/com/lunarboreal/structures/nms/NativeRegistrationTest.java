package com.lunarboreal.structures.nms;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRegistrationTest {
    @Test void registersAndRoundTripsWitchProcessorsWithoutChangingVanillaRegistryIds() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registry = BuiltInRegistries.STRUCTURE_PROCESSOR;
        var vanilla = registry.getValue(Identifier.parse("minecraft:block_rot"));
        int id = registry.getId(vanilla);
        NativeRegistries.register();
        NativeRegistries.register();
        assertSame(vanilla, registry.getValue(Identifier.parse("minecraft:block_rot")));
        assertEquals(id, registry.getId(vanilla));
        for (String name : new String[]{"leg_processor", "fence_leg_processor", "witch_circle_processor", "brewing_stand_processor", "potted_mushroom_processor"}) {
            var input = com.google.gson.JsonParser.parseString("{\"processor_type\":\"betterwitchhuts:" + name + "\"}");
            var decoded = StructureProcessorType.SINGLE_CODEC.parse(JsonOps.INSTANCE, input).getOrThrow();
            assertEquals(input, StructureProcessorType.SINGLE_CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow());
        }
    }

    @Test void registersFeatureProjectsAndRoundTripsTheirCodecs() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NativeRegistries.register();
        for (String json : new String[]{
                "{\"type\":\"yungsextras:desert_well\",\"location\":\"yungsextras:desert/wells/well_sm\",\"radius\":1}",
                "{\"type\":\"yungsextras:swamp_arch\",\"location\":\"yungsextras:swamp/arch/swamp_arch_0\"}",
                "{\"type\":\"yungsextras:desert_giant_torch\"}",
                "{\"type\":\"yungsbridges:bridge\",\"location\":\"yungsbridges:bridge/stone/15_1\",\"is_z_axis\":false}"}) {
            var input = com.google.gson.JsonParser.parseString(json);
            var decoded = net.minecraft.world.level.levelgen.feature.Feature.DIRECT_CODEC.parse(JsonOps.INSTANCE, input).getOrThrow();
            assertEquals(input, net.minecraft.world.level.levelgen.feature.Feature.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow());
        }
        var modifier = com.google.gson.JsonParser.parseString("{\"type\":\"yungsbridges:bridge\",\"length\":15,\"width\":5,\"min_water_z\":2,"
                + "\"max_water_z\":14,\"width_offset\":1,\"num_solid_blocks_needed\":2,\"is_z_axis\":false}");
        var decoded = net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.parse(JsonOps.INSTANCE, modifier).getOrThrow();
        assertEquals(modifier, net.minecraft.world.level.levelgen.placement.PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow());
    }

    @Test void preparingFeaturesLeavesTheSharedGeneratorUntouched() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var biome = net.minecraft.core.Holder.direct(new net.minecraft.world.level.biome.Biome.BiomeBuilder()
                .temperature(0.5f).downfall(0.5f)
                .specialEffects(new net.minecraft.world.level.biome.BiomeSpecialEffects.Builder().waterColor(0).build())
                .mobSpawnSettings(net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
                .generationSettings(net.minecraft.world.level.biome.BiomeGenerationSettings.EMPTY).build());
        var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(new net.minecraft.world.level.biome.FixedBiomeSource(biome),
                net.minecraft.core.Holder.direct((net.minecraft.world.level.levelgen.NoiseGeneratorSettings) null));
        var shared = generator.generationSettingsGetter;
        var prepared = WorldStructures.prepareFeatures(generator, com.lunarboreal.structures.config.GenerationPolicy.vanilla(), false, java.util.List.of());
        assertSame(shared, generator.generationSettingsGetter);
        assertNotSame(shared, prepared);
    }
}
