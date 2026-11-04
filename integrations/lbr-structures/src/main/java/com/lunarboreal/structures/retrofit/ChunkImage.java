package com.lunarboreal.structures.retrofit;

import com.lunarboreal.structures.nms.PaperAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
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

    /** Plants, fluids and snow drift on their own, and moss differs between plugin installs; none of it shows a player edit. */
    private static final Set<Block> NATURAL_DRIFT = Set.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.WATER, Blocks.LAVA, Blocks.ICE, Blocks.SNOW,
            Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH, Blocks.VINE, Blocks.GLOW_LICHEN,
            Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT, Blocks.COBWEB, Blocks.BUBBLE_COLUMN,
            Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.POINTED_DRIPSTONE, Blocks.WET_SPONGE,
            Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.LEAF_LITTER);
    private static final List<Set<Block>> SAME_MATERIAL = List.of(Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK),
            Set.of(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE),
            Set.of(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS),
            // Replays order neighbouring decoration differently, so ore veins shift; a mined ore still reads as air.
            Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.DRIPSTONE_BLOCK, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.GRAVEL, Blocks.DIRT,
                    Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
                    Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
                    Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE));
    /** Rerolled by replay or ticking; the loot table and contents stay significant. */
    private static final Set<String> VOLATILE_BLOCK_ENTITY_DATA = Set.of("keepPacked", "LootTableSeed", "Delay");

    private static boolean drifts(Block block) { return NATURAL_DRIFT.contains(block) || block instanceof net.minecraft.world.level.block.LeavesBlock; }

    /** Growth timers change without changing occupancy; every other property remains significant. */
    static boolean sameBlock(BlockState actual, BlockState expected) {
        if (actual == expected) return true;
        Block a = actual.getBlock(), b = expected.getBlock();
        if (drifts(a) && drifts(b)) return true;
        if (SAME_MATERIAL.stream().anyMatch(group -> group.contains(a) && group.contains(b))) return true;
        if (a != b) return false;
        if (a instanceof net.minecraft.world.level.block.CrossCollisionBlock || a instanceof net.minecraft.world.level.block.WallBlock) return true; // Connections follow neighbours.
        if (actual.is(Blocks.CACTUS) || actual.is(Blocks.SUGAR_CANE)) {
            var age = net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_15;
            return actual.setValue(age, 0) == expected.setValue(age, 0);
        }
        return false;
    }

    static CompoundTag stableBlockEntity(CompoundTag tag) {
        if (tag == null) return null;
        var copy = tag.copy();
        VOLATILE_BLOCK_ENTITY_DATA.forEach(copy::remove);
        return copy;
    }

    private boolean blockEntityChanged(ChunkImage other, BlockPos pos) {
        return !Objects.equals(stableBlockEntity(blockEntities.get(pos)), stableBlockEntity(other.blockEntities.get(pos)));
    }

    private boolean touchesChange(ChunkImage baseline, ChunkImage replacement, int x, int y, int z) {
        for (var side : net.minecraft.core.Direction.values()) {
            int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
            if (nx >> 4 == position.x() && nz >> 4 == position.z() && baseline.block(nx, ny, nz) != replacement.block(nx, ny, nz)) return true;
        }
        return false;
    }

    public boolean hasStart(String structure) {
        return !nbt.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").getCompoundOrEmpty(structure).getStringOr("id", "INVALID").equals("INVALID");
    }

    /**
     * Only cells the replacement changes are checked, so unrelated drift elsewhere in the box neither blocks
     * the operation nor gets overwritten. Each must still hold the vanilla replay's block, or already the new one.
     */
    public String conflict(ChunkImage baseline, ChunkImage replacement, BoundingBox bounds) {
        if (!position.equals(baseline.position) || !position.equals(replacement.position) || minY != baseline.minY || maxY != baseline.maxY) return "区块坐标或高度不一致";
        for (int y = Math.max(minY, bounds.minY()); y <= Math.min(maxY-1, bounds.maxY()); y++)
            for (int z = Math.max(position.getMinBlockZ(), bounds.minZ()); z <= Math.min(position.getMaxBlockZ(), bounds.maxZ()); z++)
                for (int x = Math.max(position.getMinBlockX(), bounds.minX()); x <= Math.min(position.getMaxBlockX(), bounds.maxX()); x++) {
                    BlockState before = baseline.block(x, y, z), after = replacement.block(x, y, z), current = block(x, y, z);
                    if (current == after || sameBlock(current, before)) continue;
                    // A player block resting on a changed cell would be left hanging even though it is never written.
                    if (before != after || touchesChange(baseline, replacement, x, y, z))
                        return "方块不一致：" + x + "," + y + "," + z + " " + current + " / " + before;
                }
        var positions = new HashSet<>(baseline.blockEntities.keySet());
        positions.addAll(replacement.blockEntities.keySet());
        for (BlockPos pos : positions) {
            if (!bounds.isInside(pos) || !baseline.blockEntityChanged(replacement, pos) && baseline.block(pos.getX(), pos.getY(), pos.getZ()) == replacement.block(pos.getX(), pos.getY(), pos.getZ())) continue;
            if (blockEntityChanged(baseline, pos) && blockEntityChanged(replacement, pos)) return "容器或方块实体内容不一致";
        }
        return null;
    }

    /** The one start an overwrite exchanges; other starts of the same type can overlap this chunk and must survive. */
    public record StructureSwap(ChunkPos start, String vanillaId, String replacementId) {}

    /** Main-thread overwrite of only the cells this image changes relative to the baseline. */
    public long replace(ServerLevel level, BoundingBox bounds, ChunkImage baseline, StructureSwap swap) {
        long changed = write(level, bounds, baseline);
        LevelChunk chunk = level.getChunk(position.x(), position.z());
        swapStructures(level, chunk, swap);
        chunk.markUnsaved();
        return changed;
    }

    /** Main-thread rollback of the whole box. */
    public long restore(ServerLevel level, BoundingBox bounds, Set<String> structures) {
        long changed = write(level, bounds, null);
        LevelChunk chunk = level.getChunk(position.x(), position.z());
        restoreStructures(level, chunk, structures);
        chunk.markUnsaved();
        return changed;
    }

    /** Side effects stay off: onPlace would prime TNT and drop sand, and removal would spill chests. */
    private long write(ServerLevel level, BoundingBox bounds, ChunkImage baseline) {
        long changed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(position.getMinBlockX() + x, y, position.getMinBlockZ() + z);
                    if (bounds != null && !bounds.isInside(pos)) continue;
                    var state = block(x, y, z);
                    if (baseline != null && baseline.block(x, y, z) == state) continue;
                    if (level.getBlockState(pos) != state) {
                        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
                        changed++;
                    }
                }
            }
        }
        LevelChunk chunk = level.getChunk(position.x(), position.z());
        for (var entry : blockEntities.entrySet()) {
            if (bounds != null && !bounds.isInside(entry.getKey())) continue;
            var at = entry.getKey();
            if (baseline != null && baseline.block(at.getX(), at.getY(), at.getZ()) == block(at.getX(), at.getY(), at.getZ()) && !blockEntityChanged(baseline, at)) continue;
            var entity = BlockEntity.loadStatic(entry.getKey(), level.getBlockState(entry.getKey()), entry.getValue().copy(), level.registryAccess());
            if (entity == null) throw new IllegalStateException("Block entity could not be restored: " + entry.getKey());
            chunk.removeBlockEntity(entry.getKey());
            chunk.addAndRegisterBlockEntity(entity);
            entity.setChanged();
        }
        return changed;
    }

    private void swapStructures(ServerLevel level, LevelChunk chunk, StructureSwap swap) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var vanilla = registry.getValue(Identifier.parse(swap.vanillaId()));
        var replacement = registry.getValue(Identifier.parse(swap.replacementId()));
        var starts = new HashMap<>(chunk.getAllStarts());
        var references = new HashMap<>(chunk.getAllReferences());
        if (vanilla != null) {
            if (position.equals(swap.start())) starts.remove(vanilla);
            var kept = new LongOpenHashSet(references.getOrDefault(vanilla, it.unimi.dsi.fastutil.longs.LongSet.of()));
            kept.remove(swap.start().pack());
            if (kept.isEmpty()) references.remove(vanilla); else references.put(vanilla, kept);
        }
        var start = savedStarts(level).get(replacement);
        if (start != null) starts.put(replacement, start);
        var added = savedReferences(level).get(replacement);
        if (added != null) {
            var merged = new LongOpenHashSet(references.getOrDefault(replacement, it.unimi.dsi.fastutil.longs.LongSet.of()));
            merged.addAll(added);
            references.put(replacement, merged);
        }
        publishStructures(level, chunk, starts, references);
    }

    private Map<Structure, StructureStart> savedStarts(ServerLevel level) {
        Map<Structure, StructureStart> starts = new HashMap<>();
        var context = StructurePieceSerializationContext.fromLevel(level);
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (var entry : nbt.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts").entrySet()) {
            if (!(entry.getValue() instanceof CompoundTag tag)) throw new IllegalStateException("Invalid saved structure");
            var structure = registry.getValue(Identifier.parse(entry.getKey()));
            if (structure == null) throw new IllegalStateException("Missing saved structure type " + entry.getKey());
            var start = StructureStart.loadStaticStart(context, tag, level.getSeed());
            if (start == null) throw new IllegalStateException("Cannot decode saved structure " + entry.getKey());
            starts.put(structure, start);
        }
        return starts;
    }

    private Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> savedReferences(ServerLevel level) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> references = new HashMap<>();
        for (var entry : nbt.getCompoundOrEmpty("structures").getCompoundOrEmpty("References").entrySet()) {
            var structure = registry.getValue(Identifier.parse(entry.getKey()));
            if (structure == null) throw new IllegalStateException("Missing referenced structure " + entry.getKey());
            references.put(structure, new LongOpenHashSet(entry.getValue().asLongArray().orElseThrow()));
        }
        return references;
    }

    private void restoreStructures(ServerLevel level, LevelChunk chunk, Set<String> replacedStructures) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var starts = savedStarts(level);
        var references = savedReferences(level);
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
        publishStructures(level, chunk, starts, references);
    }

    private void publishStructures(ServerLevel level, LevelChunk chunk, Map<Structure, StructureStart> starts,
            Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> references) {
        chunk.setAllStarts(starts);
        chunk.setAllReferences(references);
        StructureCheck check = PaperAccess.read(level.structureManager(), "structureCheck");
        check.onStructureLoad(position, starts);
    }
}
