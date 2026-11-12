package com.lunarboreal.scoreboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class PlayerModes {
    private final Path file;
    private Map<UUID, ScoreboardSettings.Mode> modes = Map.of();
    PlayerModes(Path file) { this.file = file; }
    ScoreboardSettings.Mode get(UUID id) { return modes.getOrDefault(id, ScoreboardSettings.Mode.DEFAULT); }
    void load() throws IOException {
        if (!Files.exists(file)) return;
        Map<UUID, ScoreboardSettings.Mode> next = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\t", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Bad preference row");
                ScoreboardSettings.Mode mode = ScoreboardSettings.Mode.parse(parts[1]);
                if (mode != ScoreboardSettings.Mode.DEFAULT) next.put(UUID.fromString(parts[0]), mode);
            }
        } catch (IllegalArgumentException e) { throw new IOException("Invalid scoreboard preferences", e); }
        modes = Map.copyOf(next);
    }
    void set(UUID id, ScoreboardSettings.Mode mode) throws IOException {
        Map<UUID, ScoreboardSettings.Mode> next = new HashMap<>(modes);
        if (mode == ScoreboardSettings.Mode.DEFAULT) next.remove(id); else next.put(id, mode);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "modes-", ".tmp");
        try {
            Files.write(temp, next.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + "\t" + e.getValue().name()).toList(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            modes = Map.copyOf(next);
        } finally { Files.deleteIfExists(temp); }
    }
}
