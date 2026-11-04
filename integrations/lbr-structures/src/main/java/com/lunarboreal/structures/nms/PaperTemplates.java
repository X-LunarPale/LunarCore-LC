package com.lunarboreal.structures.nms;

import com.yungnickyoung.minecraft.betterdungeons.world.DungeonContext;
import com.yungnickyoung.minecraft.yungsapi.world.processor.StructureEntityProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Runs the upstream entity processors without mutating the cached template shared by generation workers. */
public final class PaperTemplates {
    private PaperTemplates() {}

    public static boolean place(StructureTemplate source, ServerLevelAccessor level, BlockPos origin, BlockPos pivot,
                                StructurePlaceSettings settings, RandomSource random, int flags) {
        var previous = DungeonContext.pop();
        DungeonContext.initialize();
        try {
            var entityProcessors = settings.getProcessors().stream().filter(StructureEntityProcessor.class::isInstance)
                    .map(StructureEntityProcessor.class::cast).toList();
            if (entityProcessors.isEmpty() || settings.isIgnoreEntities())
                return source.placeInWorld(level, origin, pivot, settings, random, flags);
            var copy = new StructureTemplate();
            copy.palettes.addAll(source.palettes);
            PaperAccess.write(copy, "size", source.getSize());
            for (var local : source.entityInfoList) {
                var worldPos = StructureTemplate.transform(local.pos, settings.getMirror(), settings.getRotation(), settings.getRotationPivot())
                        .add(origin.getX(), origin.getY(), origin.getZ());
                var blockPos = StructureTemplate.calculateRelativePosition(settings, local.blockPos).offset(origin);
                var processed = new StructureTemplate.StructureEntityInfo(worldPos, blockPos, local.nbt.copy());
                for (var processor : entityProcessors) {
                    processed = processor.processEntity(level, origin, pivot, local, processed, settings);
                    if (processed == null) break;
                }
                if (processed != null) copy.entityInfoList.add(new StructureTemplate.StructureEntityInfo(local.pos, local.blockPos, processed.nbt));
            }
            return copy.placeInWorld(level, origin, pivot, settings, random, flags);
        } finally { DungeonContext.restore(previous); }
    }
}
