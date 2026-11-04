package com.lunarboreal.structures.retrofit;

import com.lunarboreal.structures.config.GenerationPolicy;
import com.lunarboreal.structures.config.StructureFamily;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reads saved starts only; scanning never loads/generates gameplay chunks. */
public final class RegionScanner {
    private RegionScanner() {}
    public record Progress(long chunks, long candidates) {}

    public static List<RetrofitCandidate> scan(Path region, UUID world, GenerationPolicy policy,
            BooleanSupplier cancelled, Consumer<Progress> progress) throws IOException {
        if (!Files.isDirectory(region, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var result = new ArrayList<RetrofitCandidate>();
        long chunks = 0;
        try (var files = Files.list(region)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Region file is a symbolic link");
                String[] name = file.getFileName().toString().split("\\.");
                int rx = Integer.parseInt(name[1]), rz = Integer.parseInt(name[2]);
                try (var input = new RandomAccessFile(file.toFile(), "r")) {
                    for (int index = 0; index < 1024; index++) {
                        if (cancelled.getAsBoolean()) return List.copyOf(result);
                        input.seek(index * 4L);
                        int location = input.readInt();
                        if (location == 0) continue;
                        int x = rx * 32 + (index & 31), z = rz * 32 + (index >> 5);
                        CompoundTag chunk = read(input, region, location, x, z);
                        chunks++;
                        if (!chunk.getStringOr("Status", "").equals("minecraft:full")) continue;
                        var starts = chunk.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
                        for (var entry : starts.entrySet()) {
                            String id = entry.getKey();
                            var family = Arrays.stream(StructureFamily.values()).filter(f -> policy.enabled(f) && f.vanillaStructures.contains(id)).findFirst();
                            if (family.isEmpty() || !(entry.getValue() instanceof CompoundTag start) || start.getStringOr("id", "INVALID").equals("INVALID")) continue;
                            var boxes = new ArrayList<BoundingBox>();
                            for (var tag : start.getListOrEmpty("Children")) {
                                if (!(tag instanceof CompoundTag piece)) continue;
                                int[] box = piece.getIntArray("BB").orElse(new int[0]);
                                if (box.length == 6) boxes.add(new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5]));
                            }
                            if (!boxes.isEmpty()) result.add(new RetrofitCandidate(world + "/" + id + "/" + x + "/" + z,
                                    family.get(), id, new ChunkPos(x, z), BoundingBox.encapsulatingBoxes(boxes).orElseThrow(), null));
                        }
                        if (policy.enabled(StructureFamily.DUNGEONS) && starts.isEmpty()
                                && chunk.getCompoundOrEmpty("structures").getCompoundOrEmpty("References").isEmpty()) {
                            for (var tag : chunk.getListOrEmpty("block_entities")) {
                                if (!(tag instanceof CompoundTag entity) || !Set.of("minecraft:mob_spawner", "minecraft:spawner").contains(entity.getStringOr("id", ""))) continue;
                                String mob = entity.getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getStringOr("id", "");
                                if (!Set.of("minecraft:zombie", "minecraft:skeleton", "minecraft:spider").contains(mob)) continue;
                                var pos = new BlockPos(entity.getIntOr("x", 0), entity.getIntOr("y", 0), entity.getIntOr("z", 0));
                                result.add(new RetrofitCandidate(world + "/dungeon/" + pos.toShortString(), StructureFamily.DUNGEONS,
                                        "minecraft:monster_room", new ChunkPos(x, z), new BoundingBox(pos.getX()-4, pos.getY()-1, pos.getZ()-4,
                                        pos.getX()+4, pos.getY()+4, pos.getZ()+4), pos));
                            }
                        }
                        if (chunks % 64 == 0) progress.accept(new Progress(chunks, result.size()));
                    }
                }
            }
        }
        progress.accept(new Progress(chunks, result.size()));
        return List.copyOf(result);
    }

    private static CompoundTag read(RandomAccessFile input, Path region, int location, int x, int z) throws IOException {
        long position = (location >>> 8) * 4096L;
        if (position < 8192 || position + 5 > input.length()) throw new IOException("Invalid region chunk offset");
        input.seek(position);
        int size = input.readInt(), compression = input.readUnsignedByte();
        if (size < 1 || size > 64 * 1024 * 1024) throw new IOException("Invalid region chunk size");
        InputStream raw;
        if ((compression & 128) != 0) {
            Path external = region.resolve("c." + x + "." + z + ".mcc");
            if (Files.isSymbolicLink(external)) throw new IOException("External chunk is a symbolic link");
            raw = Files.newInputStream(external);
        } else {
            if (position + 4L + size > input.length()) throw new IOException("Truncated region chunk");
            byte[] bytes = new byte[size - 1];
            input.readFully(bytes);
            raw = new ByteArrayInputStream(bytes);
        }
        var version = RegionFileVersion.fromId(compression & 127);
        if (version == null) { raw.close(); throw new IOException("Unsupported region compression"); }
        try (raw; var decoded = new DataInputStream(version.wrap(raw))) {
            return NbtIo.read(decoded, NbtAccounter.create(64L * 1024 * 1024));
        }
    }
}
