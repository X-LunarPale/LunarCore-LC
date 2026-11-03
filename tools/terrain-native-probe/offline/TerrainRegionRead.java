import com.google.gson.GsonBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Offline inspection only. Opens existing region files read-only; never starts a server. */
public final class TerrainRegionRead {
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private static final Set<String> WATCH = Set.of("minecraft:water", "minecraft:lava", "minecraft:lantern",
            "minecraft:iron_chain", "minecraft:glow_lichen", "minecraft:ice", "minecraft:packed_ice",
            "minecraft:blue_ice");

    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException(
                "Usage: TerrainRegionRead <region directory> <minY> <height> <label:x:z[:floorY]> ...");
        Path region = Path.of(args[0]).toRealPath();
        int minY = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            String[] item = args[i].split(":");
            if (item.length != 3 && item.length != 4) throw new IllegalArgumentException("Invalid coordinate: " + args[i]);
            int x = Integer.parseInt(item[1]);
            int z = Integer.parseInt(item[2]);
            Integer floorY = item.length == 4 ? Integer.parseInt(item[3]) : null;
            try {
                chunks.add(inspect(region, item[0], x, z, minY, height, floorY));
            } catch (IOException | RuntimeException failure) {
                chunks.add(Map.of("label", item[0], "x", x, "z", z, "error", failure.toString()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("regionDirectory", region.toString());
        result.put("minY", minY);
        result.put("height", height);
        result.put("scope", "Saved full chunk observations; region matches do not prove decoration provenance.");
        result.put("chunks", chunks);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }

    private static Map<String, Object> inspect(Path directory, String label, int x, int z, int minY, int height,
                                               Integer floorY)
            throws IOException {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        Path file = directory.resolve("r." + Math.floorDiv(chunkX, 32) + "." + Math.floorDiv(chunkZ, 32) + ".mca");
        CompoundTag chunk = readChunk(file, chunkX, chunkZ);
        if (chunk.getIntOr("xPos", Integer.MIN_VALUE) != chunkX
                || chunk.getIntOr("zPos", Integer.MIN_VALUE) != chunkZ) {
            throw new IOException("Stored coordinates do not match requested chunk");
        }
        Map<Integer, Section> sections = new TreeMap<>();
        List<Map<String, Object>> sectionRows = new ArrayList<>();
        Map<String, Long> blocks = new TreeMap<>();
        Map<String, Long> biomes = new TreeMap<>();
        Map<String, Observation> watched = new TreeMap<>();
        WATCH.forEach(name -> watched.put(name, new Observation()));
        for (var tag : chunk.getListOrEmpty("sections")) {
            CompoundTag section = tag.asCompound().orElseThrow(() -> new IOException("Invalid section"));
            int sectionY = section.getByteOr("Y", (byte) 0);
            CompoundTag states = section.getCompoundOrEmpty("block_states");
            ListTag palette = states.getListOrEmpty("palette");
            if (palette.isEmpty()) continue;
            int[] indices = unpack(states, palette.size(), 4096, 4);
            String[] names = new String[palette.size()];
            List<Map<String, Object>> paletteRows = new ArrayList<>();
            long[] frequencies = new long[palette.size()];
            for (int index : indices) {
                if (index < 0 || index >= palette.size()) throw new IOException("Invalid block palette index");
                frequencies[index]++;
            }
            for (int i = 0; i < palette.size(); i++) {
                CompoundTag state = palette.getCompoundOrEmpty(i);
                names[i] = palette.get(i).asString().or(() -> state.getString("id")).or(() -> state.getString("Name"))
                        .orElseThrow(() -> new IOException("Missing block name: " + palette));
                Map<String, String> properties = new TreeMap<>();
                state.getCompound("properties").or(() -> state.getCompound("Properties"))
                        .orElseGet(CompoundTag::new).forEach((key, value) ->
                        properties.put(key, value.asString().orElseThrow()));
                paletteRows.add(Map.of("name", names[i], "properties", properties, "count", frequencies[i]));
                blocks.merge(names[i], frequencies[i], Long::sum);
            }
            CompoundTag biomeData = section.getCompoundOrEmpty("biomes");
            ListTag biomePalette = biomeData.getListOrEmpty("palette");
            int[] biomeIndices = unpack(biomeData, biomePalette.size(), 64, 1);
            String[] biomeNames = new String[biomePalette.size()];
            for (int i = 0; i < biomePalette.size(); i++) biomeNames[i] = biomePalette.getStringOr(i, "<invalid>");
            for (int index : biomeIndices) {
                if (index >= biomeNames.length) throw new IOException("Invalid biome palette index");
                biomes.merge(biomeNames[index], 1L, Long::sum);
            }
            Section decoded = new Section(sectionY, indices, names, biomeIndices, biomeNames, paletteRows);
            sections.put(sectionY, decoded);
            sectionRows.add(Map.of("sectionY", sectionY, "palette", paletteRows, "biomePalette", List.of(biomeNames)));
            for (int i = 0; i < indices.length; i++) {
                String name = names[indices[i]];
                Observation observation = watched.get(name);
                if (observation != null) observation.add(chunkX * 16 + (i & 15),
                        sectionY * 16 + (i >>> 8), chunkZ * 16 + ((i >>> 4) & 15));
            }
        }
        int localX = Math.floorMod(x, 16);
        int localZ = Math.floorMod(z, 16);
        int surface = minY - 1;
        long roofedWater = 0;
        long waterBelowZero = 0;
        long waterAtRiverLevel = 0;
        for (int columnZ = 0; columnZ < 16; columnZ++) {
            for (int columnX = 0; columnX < 16; columnX++) {
                int highestSolid = minY - 1;
                for (int y = minY + height - 1; y >= minY; y--) {
                    String name = block(sections, columnX, y, columnZ);
                    if (columnX == localX && columnZ == localZ && surface < minY && !AIR.contains(name)) surface = y;
                    if (name.equals("minecraft:water")) {
                        if (highestSolid >= y + 4) roofedWater++;
                        if (y < 0) waterBelowZero++;
                        if (y >= 60 && y <= 64) waterAtRiverLevel++;
                    } else if (!AIR.contains(name) && !name.equals("minecraft:lava") && highestSolid < minY) {
                        highestSolid = y;
                    }
                }
            }
        }
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("x", x);
        target.put("z", z);
        target.put("surfaceY", surface);
        target.put("surface", block(sections, localX, surface, localZ));
        target.put("surfacePlus1", block(sections, localX, surface + 1, localZ));
        target.put("surfacePlus2", block(sections, localX, surface + 2, localZ));
        target.put("surfaceBiome", biome(sections, localX, surface, localZ));
        target.put("heightmaps", heightmaps(chunk, localX, localZ, minY, height));
        if (floorY != null) {
            target.put("anchor", Map.of("floorY", floorY,
                    "floor", blockDetail(sections, localX, floorY, localZ),
                    "above1", blockDetail(sections, localX, floorY + 1, localZ),
                    "above2", blockDetail(sections, localX, floorY + 2, localZ)));
        }
        Map<String, Object> observations = new TreeMap<>();
        watched.forEach((name, observed) -> observations.put(name, observed.result()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("regionFile", file.toString());
        result.put("chunkX", chunkX);
        result.put("chunkZ", chunkZ);
        result.put("status", chunk.getStringOr("Status", "<missing>"));
        result.put("dataVersion", chunk.getIntOr("DataVersion", -1));
        result.put("targetColumn", target);
        result.put("blockCounts", blocks);
        result.put("biomeQuartCounts", biomes);
        result.put("observations", observations);
        result.put("waterWithNonfluidBlockAtLeastFourBlocksAbove", roofedWater);
        result.put("waterBelowY0", waterBelowZero);
        result.put("waterAtY60Through64", waterAtRiverLevel);
        result.put("sections", sectionRows.stream().sorted(Comparator.comparingInt(row -> (int) row.get("sectionY"))).toList());
        return result;
    }

    private static CompoundTag readChunk(Path file, int chunkX, int chunkZ) throws IOException {
        byte[] payload;
        int compression;
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            int slot = Math.floorMod(chunkX, 32) + Math.floorMod(chunkZ, 32) * 32;
            input.seek(slot * 4L);
            int location = input.readInt();
            long offset = (location >>> 8) * 4096L;
            if (offset == 0) throw new IOException("Chunk absent from region header");
            input.seek(offset);
            int length = input.readInt();
            compression = input.readUnsignedByte();
            if ((compression & 128) != 0) {
                payload = Files.readAllBytes(file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc"));
                compression &= 127;
            } else {
                if (length < 1 || length > 64 * 1024 * 1024 || offset + 4L + length > input.length()) {
                    throw new IOException("Invalid chunk length");
                }
                payload = new byte[length - 1];
                input.readFully(payload);
            }
        }
        RegionFileVersion version = RegionFileVersion.fromId(compression);
        if (version == null) throw new IOException("Unsupported compression: " + compression);
        try (InputStream decompressed = version.wrap(new ByteArrayInputStream(payload));
             DataInputStream input = new DataInputStream(decompressed)) {
            return NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024));
        }
    }

    private static int[] unpack(CompoundTag data, int paletteSize, int size, int minimumBits) throws IOException {
        if (paletteSize < 1) throw new IOException("Empty palette");
        if (paletteSize == 1) return new int[size];
        int bits = Math.max(minimumBits, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        long[] packed = data.getLongArray("data").orElseThrow(() -> new IOException("Missing palette data"));
        SimpleBitStorage storage = new SimpleBitStorage(bits, size, packed);
        int[] result = new int[size];
        storage.unpack(result);
        return result;
    }

    private static String block(Map<Integer, Section> sections, int x, int y, int z) {
        Section section = sections.get(Math.floorDiv(y, 16));
        return section == null ? "minecraft:air" :
                section.names[section.indices[(Math.floorMod(y, 16) << 8) | (z << 4) | x]];
    }

    private static String biome(Map<Integer, Section> sections, int x, int y, int z) {
        Section section = sections.get(Math.floorDiv(y, 16));
        return section == null ? "<missing>" : section.biomes[
                section.biomeIndices[((Math.floorMod(y, 16) >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)]];
    }

    private static Map<String, Object> blockDetail(Map<Integer, Section> sections, int x, int y, int z) {
        Section section = sections.get(Math.floorDiv(y, 16));
        if (section == null) return Map.of("name", "minecraft:air", "properties", Map.of());
        int index = section.indices[(Math.floorMod(y, 16) << 8) | (z << 4) | x];
        Map<String, Object> state = section.palette.get(index);
        return Map.of("name", state.get("name"), "properties", state.get("properties"));
    }

    private static Map<String, Integer> heightmaps(CompoundTag chunk, int x, int z, int minY, int height) {
        Map<String, Integer> result = new TreeMap<>();
        int bits = 32 - Integer.numberOfLeadingZeros(height);
        chunk.getCompoundOrEmpty("Heightmaps").forEach((key, tag) -> tag.asLongArray().ifPresent(raw -> {
            SimpleBitStorage storage = new SimpleBitStorage(bits, 256, raw);
            result.put(key, storage.get((z << 4) | x) + minY);
        }));
        return result;
    }

    private record Section(int y, int[] indices, String[] names, int[] biomeIndices, String[] biomes,
                           List<Map<String, Object>> palette) {}

    private static final class Observation {
        private long count;
        private int minY = Integer.MAX_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private final List<List<Integer>> examples = new ArrayList<>();
        void add(int x, int y, int z) {
            count++;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            if (examples.size() < 8) examples.add(List.of(x, y, z));
        }
        Map<String, Object> result() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", count);
            if (count > 0) {
                result.put("minY", minY);
                result.put("maxY", maxY);
                result.put("examples", examples);
            }
            return result;
        }
    }
}
