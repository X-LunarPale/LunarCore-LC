package com.lunarboreal.structures.retrofit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lunarboreal.structures.config.ConfigStore;
import net.minecraft.nbt.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** A prepared rollback image is durable before the first gameplay-world write. */
public final class JobJournal {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    private final Map<String, Object> record;
    public final String id;
    private JobJournal(Path directory, Map<String, Object> record) {
        this.directory = directory;
        this.record = new LinkedHashMap<>(record);
        this.id = String.valueOf(record.get("id"));
    }

    public static JobJournal prepare(Path root, UUID world, String worldKey, long revision, RetrofitCandidate candidate,
            BoundingBox bounds, Set<String> structureIds, List<ChunkImage> before, List<ChunkImage> after, List<CompoundTag> entities) throws IOException {
        String id = UUID.randomUUID().toString();
        Path directory = root.resolve(id);
        Files.createDirectories(directory);
        var hashes = new LinkedHashMap<String, String>();
        for (int i = 0; i < before.size(); i++) {
            String prefix = before.get(i).position.x() + "_" + before.get(i).position.z();
            writeSnapshot(directory, "before_" + prefix + ".nbt", before.get(i).nbt, hashes);
            writeSnapshot(directory, "after_" + prefix + ".nbt", after.get(i).nbt, hashes);
        }
        var entityData = new CompoundTag();
        var list = new ListTag(); entities.forEach(t -> list.add(t.copy()));
        entityData.put("entities", list);
        writeSnapshot(directory, "entities.nbt", entityData, hashes);
        var record = new LinkedHashMap<String, Object>();
        record.put("schema", 1); record.put("id", id); record.put("world", world.toString()); record.put("worldKey", worldKey);
        record.put("revision", revision); record.put("candidate", candidate.id()); record.put("status", "PREPARED");
        record.put("bounds", List.of(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()));
        record.put("structureIds", structureIds.stream().sorted().toList());
        record.put("hashes", hashes); record.put("createdAt", java.time.Instant.now().toString());
        var journal = new JobJournal(directory, record);
        journal.persist();
        return journal;
    }

    private static void writeSnapshot(Path directory, String name, CompoundTag nbt, Map<String, String> hashes) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt, bytes);
        byte[] payload = bytes.toByteArray();
        ConfigStore.atomicWrite(directory.resolve(name), payload);
        hashes.put(name, sha256(payload));
    }

    public synchronized void status(String status) throws IOException {
        String before = (String) record.put("status", status);
        try { persist(); }
        catch (IOException error) { record.put("status", before); throw error; }
    }
    public synchronized Map<String, Object> map() { return Map.copyOf(record); }
    public synchronized String status() { return (String) record.get("status"); }
    public synchronized String world() { return (String) record.get("world"); }
    public synchronized String candidate() { return (String) record.get("candidate"); }
    public BoundingBox bounds() throws IOException {
        if (!(record.get("bounds") instanceof List<?> values) || values.size() != 6) throw new IOException("Invalid backup bounds");
        int[] b = new int[6];
        for (int i = 0; i < 6; i++) {
            if (!(values.get(i) instanceof Number n) || n.doubleValue() != n.intValue()) throw new IOException("Invalid backup coordinate");
            b[i] = n.intValue();
        }
        if (b[0]>b[3] || b[1]>b[4] || b[2]>b[5] || b[0]<-30000000 || b[3]>30000000 || b[2]<-30000000 || b[5]>30000000)
            throw new IOException("Invalid backup region");
        return new BoundingBox(b[0],b[1],b[2],b[3],b[4],b[5]);
    }
    public Set<String> structureIds() throws IOException {
        if (!(record.get("structureIds") instanceof List<?> values) || values.isEmpty()) throw new IOException("Missing backup structure identifiers");
        var ids = new HashSet<String>();
        for (Object value : values) {
            if (!(value instanceof String id) || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IOException("Invalid backup structure identifier");
            ids.add(id);
        }
        return Set.copyOf(ids);
    }

    private void persist() throws IOException {
        ConfigStore.atomicWrite(directory.resolve("journal.json"), (GSON.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public List<CompoundTag> beforeImages() throws IOException {
        var hashes = com.lunarboreal.structures.config.StructuresConfig.object(record.get("hashes"));
        var result = new ArrayList<CompoundTag>();
        for (String name : hashes.keySet().stream().filter(n -> n.startsWith("before_")).sorted().toList()) {
            if (!name.matches("before_-?\\d+_-?\\d+\\.nbt")) throw new IOException("Invalid backup filename");
            Path path = directory.resolve(name);
            if (Files.isSymbolicLink(path) || Files.size(path) > 64L * 1024 * 1024) throw new IOException("Invalid backup image");
            byte[] bytes = Files.readAllBytes(path);
            if (!sha256(bytes).equals(hashes.get(name))) throw new IOException("Backup checksum failed: " + name);
            try (var input = new ByteArrayInputStream(bytes)) { result.add(NbtIo.readCompressed(input, NbtAccounter.create(128L * 1024 * 1024))); }
        }
        if (result.isEmpty()) throw new IOException("Missing rollback images");
        return result;
    }

    public static List<JobJournal> load(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        var result = new ArrayList<JobJournal>();
        try (var directories = Files.list(root)) {
            for (Path directory : directories.sorted().toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.getFileName().toString().matches("[a-f0-9-]{36}")) continue;
                Path file = directory.resolve("journal.json");
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue; // Preparation never reached its commit point.
                if (Files.isSymbolicLink(file) || Files.size(file) > 1024 * 1024) throw new IOException("Invalid operation journal");
                try {
                    var record = com.lunarboreal.structures.config.StructuresConfig.object(GSON.fromJson(Files.readString(file), Object.class));
                    if (!directory.getFileName().toString().equals(record.get("id"))) throw new IOException("Journal ID mismatch");
                    UUID.fromString((String) record.get("id"));
                    if (com.lunarboreal.structures.config.StructuresConfig.integer(record.get("schema")) != 1) throw new IOException("Unsupported journal schema");
                    UUID.fromString((String) record.get("world"));
                    if (!Set.of("PREPARED", "APPLYING", "COMMITTED", "ROLLING_BACK", "ROLLED_BACK", "RECOVERY_REQUIRED").contains(record.get("status")))
                        throw new IOException("Unknown journal state");
                    result.add(new JobJournal(directory, record));
                } catch (RuntimeException error) { throw new IOException("Unreadable operation journal", error); }
            }
        }
        return List.copyOf(result);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}
