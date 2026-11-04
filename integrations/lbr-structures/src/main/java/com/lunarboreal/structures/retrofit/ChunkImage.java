package com.lunarboreal.structures.retrofit;

import com.lunarboreal.structures.nms.PaperAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import java.util.*;

/** Detached chunk data, suitable for comparison on an IO worker and durable rollback. */
public final class ChunkImage {
    public final CompoundTag nbt;
    public final ChunkPos position;
    private final Map<Integer, LevelChunkSection> sections = new HashMap<>();
    private final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
    private final int minY, maxY;

    public ChunkImage(ServerLevel level, CompoundTag nbt) {
        this.nbt = nbt.copy();
        this.position = new ChunkPos(nbt.getIntOr("xPos", Integer.MIN_VALUE), nbt.getIntOr("zPos", Integer.MIN_VALUE));
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
        var data = SerializableChunkData.parse(level, level.palettedContainerFactory(), this.nbt);
        if (data == null) throw new IllegalArgumentException("Unreadable chunk snapshot");
        for (var section : data.sectionData()) if (section.chunkSection() != null) sections.put(section.y(), section.chunkSection());
        for (var entry : nbt.getListOrEmpty("block_entities")) {
            if (!(entry instanceof CompoundTag tag)) throw new IllegalArgumentException("Invalid block entity snapshot");
            var copy = tag.copy();
            copy.remove("keepPacked");
            blockEntities.put(new BlockPos(copy.getIntOr("x", 0), copy.getIntOr("y", 0), copy.getIntOr("z", 0)), copy);
        }
    }

    public static ChunkImage capture(ServerLevel level, LevelChunk chunk) {
        return new ChunkImage(level, SerializableChunkData.copyOf(level, chunk).write());
    }

    public BlockState block(int x, int y, int z) {
        var section = sections.get(y >> 4);
        return section == null ? Blocks.AIR.defaultBlockState() : section.getBlockState(x & 15, y & 15, z & 15);
    }

    /** Growth timers change without changing occupancy; every other property remains significant. */
    static boolean sameBlock(BlockState actual, BlockState expected) {
        if (actual == expected) return true;
        if (actual.getBlock() != expected.getBlock()) return false;
        if (actual.is(Blocks.CACTUS) || actual.is(Blocks.SUGAR_CANE)) {
            var age = net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_15;
            return actual.setValue(age, 0) == expected.setValue(age, 0);
        }
        return false;
    }

    /** Includes the expansion footprint, container inventories and signs, not just the old structure blocks. */
    public boolean sameContents(ChunkImage expected) {
        return difference(expected, null) == null;
    }

    public boolean sameContents(ChunkImage expected, BoundingBox bounds) { return difference(expected, bounds) == null; }

    public String difference(ChunkImage expected, BoundingBox bounds) {
        if (!position.equals(expected.position) || minY != expected.minY || maxY != expected.maxY) return "区块坐标或高度不一致";
        if (bounds != null) {
            var actualEntities = new HashMap<BlockPos, CompoundTag>();
            var expectedEntities = new HashMap<BlockPos, CompoundTag>();
            blockEntities.forEach((p, tag) -> { if (bounds.isInside(p)) actualEntities.put(p, tag); });
            expected.blockEntities.forEach((p, tag) -> { if (bounds.isInside(p)) expectedEntities.put(p, tag); });
            if (!actualEntities.equals(expectedEntities)) return "容器或方块实体内容不一致";
            for (int y = Math.max(minY, bounds.minY()); y <= Math.min(maxY-1, bounds.maxY()); y++)
                for (int z = Math.max(position.getMinBlockZ(), bounds.minZ()); z <= Math.min(position.getMaxBlockZ(), bounds.maxZ()); z++)
                    for (int x = Math.max(position.getMinBlockX(), bounds.minX()); x <= Math.min(position.getMaxBlockX(), bounds.maxX()); x++)
                        if (!sameBlock(block(x, y, z), expected.block(x, y, z))) return "方块不一致：" + x + "," + y + "," + z + " " + block(x,y,z) + " / " + expected.block(x,y,z);
            return null;
        }
        if (!position.equals(expected.position) || minY != expected.minY || maxY != expected.maxY || !blockEntities.equals(expected.blockEntities)) return "区块或方块实体不一致";
        for (int sy = minY >> 4; sy < maxY >> 4; sy++) {
            var a = sections.get(sy); var b = expected.sections.get(sy);
            if ((a == null) != (b == null)) return "区块段不一致";
            if (a != null) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
                if (!a.getNoiseBiome(x, y, z).equals(b.getNoiseBiome(x, y, z))) return "生物群系不一致";
        }
        for (int y = minY; y < maxY; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) if (block(x, y, z) != expected.block(x, y, z)) return "方块不一致";
        return null;
    }

    /** Main-thread application; null bounds restores the complete snapshot during rollback. */
    public long apply(ServerLevel level, BoundingBox bounds, Set<String> replacedStructures) {
        long changed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(position.getMinBlockX() + x, y, position.getMinBlockZ() + z);
                    if (bounds != null && !bounds.isInside(pos)) continue;
                    var state = block(x, y, z);
                    if (level.getBlockState(pos) != state) {
                        level.setBlock(pos, state, 18);
                        changed++;
                    }
                }
            }
        }
        LevelChunk chunk = level.getChunk(position.x(), position.z());
        for (var entry : blockEntities.entrySet()) {
            if (bounds != null && !bounds.isInside(entry.getKey())) continue;
            var entity = BlockEntity.loadStatic(entry.getKey(), level.getBlockState(entry.getKey()), entry.getValue().copy(), level.registryAccess());
            if (entity == null) throw new IllegalStateException("Block entity could not be restored: " + entry.getKey());
            chunk.removeBlockEntity(entry.getKey());
            chunk.setBlockEntity(entity);
            entity.setChanged();
        }
        restoreStructures(level, chunk, replacedStructures);
        chunk.markUnsaved();
        return changed;
    }

    private void restoreStructures(ServerLevel level, LevelChunk chunk, Set<String> replacedStructures) {
        var saved = nbt.getCompoundOrEmpty("structures");
        Map<Structure, StructureStart> starts = new HashMap<>();
        var context = StructurePieceSerializationContext.fromLevel(level);
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (var entry : saved.getCompoundOrEmpty("starts").entrySet()) {
            if (!(entry.getValue() instanceof CompoundTag tag)) throw new IllegalStateException("Invalid saved structure");
            var structure = registry.getValue(Identifier.parse(entry.getKey()));
            if (structure == null) throw new IllegalStateException("Missing saved structure type " + entry.getKey());
            var start = StructureStart.loadStaticStart(context, tag, level.getSeed());
            if (start == null) throw new IllegalStateException("Cannot decode saved structure " + entry.getKey());
            starts.put(structure, start);
        }
        Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> references = new HashMap<>();
        for (var entry : saved.getCompoundOrEmpty("References").entrySet()) {
            var structure = registry.getValue(Identifier.parse(entry.getKey()));
            if (structure == null) throw new IllegalStateException("Missing referenced structure " + entry.getKey());
            references.put(structure, new LongOpenHashSet(entry.getValue().asLongArray().orElseThrow()));
        }
        if (replacedStructures != null) {
            var preservedStarts = new HashMap<>(chunk.getAllStarts());
            var preservedReferences = new HashMap<>(chunk.getAllReferences());
            for (String id : replacedStructures) {
                var structure = registry.getValue(Identifier.parse(id));
                if (structure == null) continue;
                preservedStarts.remove(structure);
                preservedReferences.remove(structure);
                if (starts.containsKey(structure)) preservedStarts.put(structure, starts.get(structure));
                if (references.containsKey(structure)) preservedReferences.put(structure, references.get(structure));
            }
            starts = preservedStarts;
            references = preservedReferences;
        }
        chunk.setAllStarts(starts);
        chunk.setAllReferences(references);
        StructureCheck check = PaperAccess.read(level.structureManager(), "structureCheck");
        check.onStructureLoad(position, starts);
    }
}
