package com.lunarboreal.structures.retrofit;

import com.lunarboreal.structures.config.StructureFamily;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record RetrofitCandidate(String id, StructureFamily family, String vanillaId, ChunkPos start,
                                BoundingBox bounds, BlockPos dungeonOrigin) {}
