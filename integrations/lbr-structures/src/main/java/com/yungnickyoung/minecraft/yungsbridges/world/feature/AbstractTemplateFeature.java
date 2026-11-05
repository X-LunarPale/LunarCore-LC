// Paper port; original source and LGPL-3.0 notice: third-party/yungs/bridges/
package com.yungnickyoung.minecraft.yungsbridges.world.feature;

import com.yungnickyoung.minecraft.yungsbridges.YungsBridgesCommon;
import com.yungnickyoung.minecraft.yungsbridges.world.processor.ITemplateFeatureProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Convenience class for easily generating features from structure NBT.
 * Includes functionality for applying additional processing after initial generation,
 * similar to Jigsaw structures.
 */
public abstract class AbstractTemplateFeature implements Feature {
    protected List<ITemplateFeatureProcessor> processors;

    protected AbstractTemplateFeature() {
        this.processors = useProcessors();
    }

    /**
     * Generates the template feature with default placement settings and applies processors.
     * @param id ID of this template feature (i.e. namespaced path to the structure NBT)
     * @param level WorldGenLevel
     * @param randomSource RandomSource
     * @param pos The position to generate the feature at. This will be the corner of the feature.
     * @return The generated Template
     */
    protected StructureTemplate createTemplate(Identifier id, WorldGenLevel level, RandomSource randomSource, BlockPos pos) {
        return createTemplateWithPlacement(id, level, randomSource, pos, new StructurePlaceSettings());
    }

    /**
     * Generates the template feature and applies processors.
     * @param id ID of this template feature (i.e. namespaced path to the structure NBT)
     * @param level WorldGenLevel
     * @param randomSource RandomSource
     * @param cornerPos The position to generate the feature at. This will be the corner of the feature.
     * @param placement Placement settings for the feature
     * @return The generated Template
     */
    protected StructureTemplate createTemplateWithPlacement(
        Identifier id,
        WorldGenLevel level,
        RandomSource randomSource,
        BlockPos cornerPos,
        StructurePlaceSettings placement
    ) {
        Optional<StructureTemplate> templateOptional = level.getLevel().getStructureTemplateManager().get(id);

        if (templateOptional.isEmpty()) { // Unsuccessful creation. Name is probably invalid.
            YungsBridgesCommon.LOGGER.warn("Failed to create invalid feature {}", id);
            return null;
        }

        StructureTemplate template = templateOptional.get();

        // Create & place template
        BlockPos centerPos = cornerPos.offset(template.getSize().getX() / 2, 0, template.getSize().getZ() / 2);
        template.placeInWorld(level, cornerPos, centerPos, placement, randomSource, 2);

        // Additional optional processing
        processors.forEach(processor -> processor.processTemplate(template, level, randomSource, cornerPos, centerPos, placement));

        return template;
    }

    /**
     * Attaches specified TemplateFeatureProcessors to this feature.
     * Override this method to return whatever processors you want this feature to have.
     */
    protected List<ITemplateFeatureProcessor> useProcessors() {
        return new ArrayList<>();
    }
}
