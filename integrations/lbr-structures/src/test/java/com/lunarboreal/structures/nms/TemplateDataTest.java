package com.lunarboreal.structures.nms;

import com.yungnickyoung.minecraft.betterdeserttemples.world.processor.PotProcessor;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateDataTest {
    @Test void randomizedPotDecorationsDecodeUsingTheActual263CodecWithoutMutatingTheTemplate() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var original = new CompoundTag(); original.putString("custom", "preserved");
        var info = new StructureTemplate.StructureBlockInfo(BlockPos.ZERO, Blocks.DECORATED_POT.defaultBlockState(), original);
        var processed = PotProcessor.INSTANCE.processBlock(null, BlockPos.ZERO, BlockPos.ZERO, info, info, new StructurePlaceSettings());
        var ops = RegistryOps.create(NbtOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var decoded = PotDecorations.CODEC.parse(ops, processed.nbt().get("sherds")).getOrThrow();
        assertTrue(decoded.back().isPresent()); assertTrue(decoded.front().isPresent());
        assertTrue(decoded.left().isPresent()); assertTrue(decoded.right().isPresent());
        assertFalse(original.contains("sherds"));
        assertEquals("preserved", processed.nbt().getStringOr("custom", ""));
    }

    @Test void shippedTemplatesDoNotContainForeignAttributesThatInvalidateMinecraftEntityAttributes() throws Exception {
        try (var paths = Files.walk(Path.of("src/main/resources/yungs/data"))) {
            var templates = paths.filter(p -> p.toString().endsWith(".nbt")).toList();
            assertTrue(templates.size() > 100);
            for (Path path : templates) verify(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()), path);
        }
    }

    private void verify(Tag tag, Path path) {
        if (tag instanceof CompoundTag compound) {
            for (String key : new String[]{"Attributes", "attributes"})
                for (Tag entry : compound.getListOrEmpty(key))
                    if (entry instanceof CompoundTag attribute) {
                        String id = attribute.getStringOr("Name", attribute.getStringOr("id", ""));
                        assertFalse(id.startsWith("forge:"), () -> path + ": " + id);
                    }
            for (Tag nested : compound.values()) verify(nested, path);
        } else if (tag instanceof ListTag list) for (Tag nested : list) verify(nested, path);
    }
}
