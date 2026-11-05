// Paper port; original source and LGPL-3.0 notice: third-party/yungs/bridges/
package com.yungnickyoung.minecraft.yungsbridges.world.feature;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.resources.Identifier;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.google.common.collect.Lists;
import com.yungnickyoung.minecraft.yungsbridges.module.FeatureProcessorModule;
import com.yungnickyoung.minecraft.yungsbridges.world.processor.ITemplateFeatureProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

import java.util.List;

public class BridgeFeature extends AbstractTemplateFeature {
    public static final MapCodec<BridgeFeature> CODEC = RecordCodecBuilder.mapCodec(builder -> builder.group(
            Identifier.CODEC.fieldOf("location").forGetter(feature -> feature.id),
            Codec.BOOL.optionalFieldOf("is_z_axis", true).forGetter(feature -> feature.isZAxis)
    ).apply(builder, BridgeFeature::new));

    /** Identifier pointing to the structure's NBT */
    private final Identifier id;

    /** Rotation of the bridge. True if bridge should go north-south, false if east-west. */
    private final boolean isZAxis;

    /**
     * List of processors this template feature will use right after generation.
     */
    private static final List<ITemplateFeatureProcessor> PROCESSORS = Lists.newArrayList(
            FeatureProcessorModule.LEG_PROCESSOR,
            FeatureProcessorModule.LOG_BIOME_PROCESSOR,
            FeatureProcessorModule.STAIR_BIOME_PROCESSOR,
            FeatureProcessorModule.PLANKS_BIOME_PROCESSOR,
            FeatureProcessorModule.SLAB_BIOME_PROCESSOR,
            FeatureProcessorModule.STONE_VARIATION_PROCESSOR,
            FeatureProcessorModule.LANTERN_ROT_PROCESSOR,
            FeatureProcessorModule.OPTIONAL_WALL_PROCESSOR,
            FeatureProcessorModule.OPTIONAL_STONE_BRICK_PROCESSOR,
            FeatureProcessorModule.OPTIONAL_SLAB_PROCESSOR,
            FeatureProcessorModule.OPTIONAL_STAIR_PROCESSOR,
            FeatureProcessorModule.FENCE_BIOME_PROCESSOR
    );

    private BridgeFeature(Identifier id, boolean isZAxis) {
        this.id = id;
        this.isZAxis = isZAxis;
    }

    @Override public MapCodec<BridgeFeature> codec() { return CODEC; }

    /**
     * We override this method to supply the processors specific to this template feature.
     */
    @Override
    protected List<ITemplateFeatureProcessor> useProcessors() {
        return PROCESSORS;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        BlockPos.MutableBlockPos startPos = origin.mutable();
        startPos.setY(level.getSeaLevel()); // Starting pos will be center of bridge

        // Generate the feature. Implicitly applies processors after generation.
        // Shape and waterlogging passes read one block past the template, which leaves the safe
        // range for a bridge ending at the write limit. Templates store their final shapes, and
        // sit at sea level, above the water.
        StructurePlaceSettings placementSettings = new StructurePlaceSettings().setKnownShape(true).setLiquidSettings(net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.IGNORE_WATERLOGGING);
        if (!isZAxis) placementSettings.setRotation(Rotation.COUNTERCLOCKWISE_90);
        return this.createTemplateWithPlacement(id, level, random, startPos, placementSettings) != null;
    }
}
