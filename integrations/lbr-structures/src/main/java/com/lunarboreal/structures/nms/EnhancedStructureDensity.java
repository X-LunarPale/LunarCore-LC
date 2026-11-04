package com.lunarboreal.structures.nms;

import com.yungnickyoung.minecraft.yungsapi.world.structure.YungJigsawStructure;
import com.yungnickyoung.minecraft.yungsapi.world.structure.jigsaw.element.YungJigsawPoolElement;
import com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.adaptations.EnhancedTerrainAdaptation;
import com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.adaptations.NoneAdaptation;
import com.yungnickyoung.minecraft.yungsapi.world.structure.terrainadaptation.aquiferoverride.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import java.util.ArrayList;
import java.util.List;

/**
 * 26.3 DensitySampler port of YUNG's EnhancedBeardifierHelper (LGPL-3.0).
 * The padding, kernel, rigid/junction weights and fluid override ordering follow
 * the pinned upstream implementation. State belongs to one generation operation.
 */
public final class EnhancedStructureDensity extends Beardifier {
    private record Piece(BoundingBox bounds, EnhancedTerrainAdaptation adaptation) {}
    private record Junction(int x, int y, int z, EnhancedTerrainAdaptation adaptation) {}
    private final Beardifier vanilla;
    private final List<Piece> pieces = new ArrayList<>();
    private final List<Junction> junctions = new ArrayList<>();

    public EnhancedStructureDensity(StructureManager manager, ChunkPos chunk) {
        super(List.of(), List.of(), null);
        vanilla = Beardifier.forStructuresInChunk(manager, chunk);
        for (var start : manager.startsForStructure(chunk.x(), chunk.z(), structure -> structure instanceof YungJigsawStructure)) {
            var structureAdaptation = ((YungJigsawStructure) start.getStructure()).enhancedTerrainAdaptation;
            int radius = structureAdaptation.getKernelRadius();
            for (var piece : start.getPieces()) {
                if (piece instanceof PoolElementStructurePiece pool && pool.getElement() instanceof YungJigsawPoolElement element
                        && element.getEnhancedTerrainAdaptation().isPresent())
                    radius = Math.max(radius, element.getEnhancedTerrainAdaptation().get().getKernelRadius());
            }
            if (radius <= 0) continue;
            for (var piece : start.getPieces()) {
                if (!piece.isCloseToChunk(chunk, radius)) continue;
                var adaptation = structureAdaptation;
                if (piece instanceof PoolElementStructurePiece pool) {
                    if (pool.getElement() instanceof YungJigsawPoolElement element)
                        adaptation = element.getEnhancedTerrainAdaptation().orElse(adaptation);
                    if (adaptation == NoneAdaptation.INSTANCE) continue;
                    if (pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID)
                        pieces.add(new Piece(padded(piece.getBoundingBox(), pool.getRotation(), adaptation), adaptation));
                    int kernel = adaptation.getKernelRadius();
                    for (var join : pool.getJunctions()) {
                        int x = join.getSourceX(), z = join.getSourceZ();
                        if (x > chunk.getMinBlockX() - kernel && z > chunk.getMinBlockZ() - kernel
                                && x < chunk.getMinBlockX() + 15 + kernel && z < chunk.getMinBlockZ() + 15 + kernel)
                            junctions.add(new Junction(x, join.getSourceGroundY() + (int) adaptation.getBottomOffset(), z, adaptation));
                    }
                } else if (adaptation != NoneAdaptation.INSTANCE)
                    pieces.add(new Piece(padded(piece.getBoundingBox(), Rotation.NONE, adaptation), adaptation));
            }
        }
    }

    private static BoundingBox padded(BoundingBox box, Rotation rotation, EnhancedTerrainAdaptation adaptation) {
        var padding = adaptation.getPadding();
        boolean swapped = rotation.rotate(Direction.EAST).getAxis() != Direction.Axis.X;
        int x = swapped ? padding.z() : padding.x(), z = swapped ? padding.x() : padding.z();
        int offset = (int) adaptation.getBottomOffset();
        return new BoundingBox(box.minX() - x, box.minY() + offset - padding.bottom(), box.minZ() - z,
                box.maxX() + x, box.maxY() + offset + padding.top(), box.maxZ() + z);
    }

    public boolean hasAdaptations() { return !pieces.isEmpty() || !junctions.isEmpty(); }

    private double contribution(Piece piece, int x, int y, int z) {
        var b = piece.bounds;
        return piece.adaptation.computeDensityFactor(Math.max(0, Math.max(b.minX() - x, x - b.maxX())),
                Math.max(0, Math.max(b.minY() - y, y - b.maxY())),
                Math.max(0, Math.max(b.minZ() - z, z - b.maxZ())), y - b.minY()) * .8;
    }

    private double contribution(Junction junction, int x, int y, int z) {
        return junction.adaptation.computeDensityFactor(x - junction.x, y - junction.y, z - junction.z, y - junction.y) * .4;
    }

    @Override public float sampleValue(SamplerContext context, int x, int y, int z) {
        double density = vanilla.sampleValue(context, x, y, z);
        for (var piece : pieces) density += contribution(piece, x, y, z);
        for (var junction : junctions) density += contribution(junction, x, y, z);
        return (float) density;
    }

    @Override public void sampleVolume(SamplerContext context, DensityBuffer target, DensityVolume volume) {
        DensitySampler.sampleVolumeNaive(context, target, volume, this);
    }

    public BlockState fluid(int x, int y, int z, BlockState original, BlockState solid) {
        if (original == null || !(original.is(Blocks.WATER) || original.is(Blocks.LAVA))) return original;
        AquiferOverride override = NoneAquiferOverride.INSTANCE;
        for (var piece : pieces)
            if (piece.adaptation.getAquiferOverride() != NoneAquiferOverride.INSTANCE && contribution(piece, x, y, z) != 0)
                override = piece.adaptation.getAquiferOverride();
        for (var junction : junctions)
            if (junction.adaptation.getAquiferOverride() != NoneAquiferOverride.INSTANCE && contribution(junction, x, y, z) != 0)
                override = junction.adaptation.getAquiferOverride();
        // Do not mutate the upstream shared singleton: Overworld/Nether generation can overlap.
        return override instanceof SolidifyAquiferOverride ? solid : override.getBlockState(original);
    }
}
