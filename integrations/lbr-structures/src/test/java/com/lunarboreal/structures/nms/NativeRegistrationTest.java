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
}
