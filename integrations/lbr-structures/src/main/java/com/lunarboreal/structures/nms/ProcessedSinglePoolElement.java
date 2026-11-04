package com.lunarboreal.structures.nms;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import java.util.Optional;
import java.util.function.Function;

/** Vanilla single-piece placement with the upstream entity and dungeon-context hooks. */
public class ProcessedSinglePoolElement extends SinglePoolElement {
    public static final MapCodec<ProcessedSinglePoolElement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            templateCodec(), processorsCodec(), projectionCodec(), overrideLiquidSettingsCodec(),
            Codec.BOOL.optionalFieldOf("legacy", false).forGetter((ProcessedSinglePoolElement e) -> e.legacy)
    ).apply(instance, ProcessedSinglePoolElement::new));
    public static final StructurePoolElementType<ProcessedSinglePoolElement> TYPE = () -> CODEC;
    private final boolean legacy;

    public ProcessedSinglePoolElement(Either<Identifier, StructureTemplate> template, Holder<StructureProcessorList> processors,
            StructureTemplatePool.Projection projection, Optional<LiquidSettings> liquids) {
        this(template, processors, projection, liquids, false);
    }

    public ProcessedSinglePoolElement(Either<Identifier, StructureTemplate> template, Holder<StructureProcessorList> processors,
            StructureTemplatePool.Projection projection, Optional<LiquidSettings> liquids, boolean legacy) {
        super(template, processors, projection, liquids);
        this.legacy = legacy;
    }

    @Override protected StructurePlaceSettings getSettings(Rotation rotation, BoundingBox bounds, LiquidSettings liquids, boolean jigsaws) {
        var settings = super.getSettings(rotation, bounds, liquids, jigsaws);
        if (legacy) {
            settings.popProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
            settings.addProcessor(BlockIgnoreProcessor.STRUCTURE_AND_AIR);
        }
        return settings;
    }

    @Override public StructurePoolElementType<?> getType() { return TYPE; }

    @Override public boolean place(StructureTemplateManager manager, WorldGenLevel level, StructureManager structures,
            ChunkGenerator generator, BlockPos origin, BlockPos pivot, Rotation rotation, BoundingBox bounds,
            RandomSource random, LiquidSettings liquids, boolean jigsaws) {
        var source = template.map(manager::getOrCreate, Function.identity());
        var settings = getSettings(rotation, bounds, liquids, jigsaws);
        if (!PaperTemplates.place(source, level, origin, pivot, settings, random, 18)) return false;
        for (var marker : StructureTemplate.processBlockInfos(level, origin, pivot, settings, getDataMarkers(manager, origin, rotation, false)))
            handleDataMarker(level, marker, origin, rotation, random, bounds);
        return true;
    }
}
