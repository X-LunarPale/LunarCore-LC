package com.lunarboreal.structures.nms;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import java.util.concurrent.ConcurrentHashMap;

/** Stable vanilla registry names for constants grouped/renamed in the 26.3 Java API. */
public final class PaperBlocks {
    private PaperBlocks() {}
    private static final ConcurrentHashMap<String, Block> BLOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, TagKey<Block>> TAGS = new ConcurrentHashMap<>();

    public static Block block(String name) {
        return BLOCKS.computeIfAbsent(name, key -> {
            var id = Identifier.withDefaultNamespace(key);
            if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalStateException("Missing vanilla block: " + id);
            return BuiltInRegistries.BLOCK.getValue(id);
        });
    }

    public static TagKey<Block> tag(String name) {
        return TAGS.computeIfAbsent(name, key -> TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(key)));
    }

    public static net.minecraft.core.BlockPos copy(net.minecraft.core.Vec3i pos) {
        return new net.minecraft.core.BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }
}
