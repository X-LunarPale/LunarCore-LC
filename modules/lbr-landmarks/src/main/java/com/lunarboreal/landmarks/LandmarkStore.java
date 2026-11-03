package com.lunarboreal.landmarks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Properties;
import java.util.UUID;

final class LandmarkStore {
    static final long MAX_BYTES = 32L * 1024 * 1024;
    private final Path file;
    LandmarkStore(Path file) { this.file = file; }
    LandmarkBook load() throws IOException {
        if (Files.notExists(file)) return LandmarkBook.empty();
        if (Files.size(file) > MAX_BYTES) throw new IOException("Landmark store exceeds size limit");
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Landmark store exceeds size limit");
            properties.load(new java.io.ByteArrayInputStream(bytes));
        } catch (IllegalArgumentException malformed) { throw new IOException("Invalid landmark store", malformed); }
        try {
            if (!"1".equals(properties.getProperty("format"))) throw new IllegalArgumentException("format");
            int count = Integer.parseInt(required(properties, "count"));
            if (count < 0 || count > LandmarkBook.MAX_ENTRIES) throw new IllegalArgumentException("count");
            var entries = new ArrayList<Landmark>();
            for (int i = 0; i < count; i++) {
                String prefix = "entry." + i + ".";
                entries.add(new Landmark(number(properties, prefix + "id"),
                        UUID.fromString(required(properties, prefix + "owner")),
                        required(properties, prefix + "ownerName"), number(properties, prefix + "landId"),
                        required(properties, prefix + "world"), integer(properties, prefix + "x"),
                        integer(properties, prefix + "y"), integer(properties, prefix + "z"),
                        required(properties, prefix + "title"), required(properties, prefix + "description"),
                        number(properties, prefix + "createdAt"), number(properties, prefix + "updatedAt"),
                        number(properties, prefix + "revision"),
                        Landmark.Status.valueOf(required(properties, prefix + "status")),
                        required(properties, prefix + "reviewer"), required(properties, prefix + "reviewNote")));
            }
            return new LandmarkBook(number(properties, "nextId"), entries);
        } catch (RuntimeException error) { throw new IOException("Invalid landmark store", error); }
    }
    void save(LandmarkBook state) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("format", "1");
        properties.setProperty("nextId", Long.toString(state.nextId()));
        properties.setProperty("count", Integer.toString(state.entries().size()));
        for (int i = 0; i < state.entries().size(); i++) {
            Landmark entry = state.entries().get(i);
            String prefix = "entry." + i + ".";
            properties.setProperty(prefix + "id", Long.toString(entry.id()));
            properties.setProperty(prefix + "owner", entry.owner().toString());
            properties.setProperty(prefix + "ownerName", entry.ownerName());
            properties.setProperty(prefix + "landId", Long.toString(entry.landId()));
            properties.setProperty(prefix + "world", entry.world());
            properties.setProperty(prefix + "x", Integer.toString(entry.x()));
            properties.setProperty(prefix + "y", Integer.toString(entry.y()));
            properties.setProperty(prefix + "z", Integer.toString(entry.z()));
            properties.setProperty(prefix + "title", entry.title());
            properties.setProperty(prefix + "description", entry.description());
            properties.setProperty(prefix + "createdAt", Long.toString(entry.createdAt()));
            properties.setProperty(prefix + "updatedAt", Long.toString(entry.updatedAt()));
            properties.setProperty(prefix + "revision", Long.toString(entry.revision()));
            properties.setProperty(prefix + "status", entry.status().name());
            properties.setProperty(prefix + "reviewer", entry.reviewer());
            properties.setProperty(prefix + "reviewNote", entry.reviewNote());
        }
        var output = new ByteArrayOutputStream();
        properties.store(output, "LunarCore landmarks");
        atomicWrite(file, output.toByteArray());
    }
    static void atomicWrite(Path target, byte[] data) throws IOException {
        if (data.length > MAX_BYTES) throw new IOException("Landmark store exceeds size limit");
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static long number(Properties properties, String key) { return Long.parseLong(required(properties, key)); }
    private static int integer(Properties properties, String key) { return Integer.parseInt(required(properties, key)); }
}
