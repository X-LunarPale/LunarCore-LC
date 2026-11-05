// Paper port; original source and LGPL-3.0 notice: third-party/yungs/bridges/
package com.yungnickyoung.minecraft.yungsbridges.world.feature;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Randomly iterates a list of features, attempting to generate each one.
 * Stops upon successful generation of a feature.
 */
public record MultipleAttemptSingleRandomFeature(HolderSet<PlacedFeature> features) implements Feature {
    public static final MapCodec<MultipleAttemptSingleRandomFeature> CODEC = PlacedFeature.LIST_CODEC.fieldOf("features")
            .xmap(MultipleAttemptSingleRandomFeature::new, MultipleAttemptSingleRandomFeature::features);

    @Override public MapCodec<MultipleAttemptSingleRandomFeature> codec() { return CODEC; }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        List<PlacedFeature> placedFeatureList = new ArrayList<>(features.stream().map(Holder::value).toList());

        while (!placedFeatureList.isEmpty()) {
            // Get random feature from placedFeatureList
            int i = random.nextInt(placedFeatureList.size());

            // If it successfully generates, return true
            if (placedFeatureList.get(i).place(level, generator, random, origin)) return true;

            // Else, remove it from the placedFeatureList and continue
            placedFeatureList.remove(i);
        }

        return false; // No feature generated
    }

    @Override
    public Stream<Holder<Feature>> getSubFeatures() {
        return features.stream().flatMap(feature -> feature.value().getFeatures());
    }
}
