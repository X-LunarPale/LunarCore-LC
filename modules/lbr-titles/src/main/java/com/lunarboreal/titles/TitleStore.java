package com.lunarboreal.titles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Copy-on-write state: publish only after an atomic durable replacement succeeds. */
final class TitleStore {
    record State(Set<String> owned, String equipped) {
        State { owned = Set.copyOf(owned); }
    }
    private final Path file;
    private volatile Map<UUID, State> states = Map.of();
    TitleStore(Path file) { this.file = file; }
    State get(UUID id) { return states.getOrDefault(id, new State(Set.of(), "")); }
    Map<UUID, State> snapshot() { return states; }
    void load() throws IOException {
        if (!Files.exists(file)) return;
        Map<UUID, State> loaded = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length != 3) throw new IOException("Invalid title state row");
            try {
                Set<String> owned = new HashSet<>();
                if (!parts[1].isEmpty()) for (String title : parts[1].split(",")) owned.add(TitleDefinition.validId(title));
                String equipped = parts[2].isEmpty() ? "" : TitleDefinition.validId(parts[2]);
                if (!equipped.isEmpty() && !owned.contains(equipped)) throw new IllegalArgumentException("Equipped title is not owned");
                loaded.put(UUID.fromString(parts[0]), new State(owned, equipped));
            } catch (IllegalArgumentException ex) { throw new IOException("Invalid title state", ex); }
        }
        states = Map.copyOf(loaded);
    }
    synchronized void change(UUID player, String action, String title) throws IOException {
        State previous = get(player);
        Set<String> owned = new HashSet<>(previous.owned());
        String equipped = previous.equipped();
        switch (action) {
            case "grant" -> owned.add(title);
            case "revoke" -> { owned.remove(title); if (equipped.equals(title)) equipped = ""; }
            case "equip" -> { if (!owned.contains(title)) throw new IllegalArgumentException("尚未拥有该称号"); equipped = title; }
            case "clear" -> equipped = "";
            default -> throw new IllegalArgumentException("未知操作");
        }
        Map<UUID, State> next = new HashMap<>(states);
        if (owned.isEmpty() && equipped.isEmpty()) next.remove(player);
        else next.put(player, new State(owned, equipped));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "titles-", ".tmp");
        try {
            List<String> rows = new ArrayList<>();
            next.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> rows.add(e.getKey() + "\t" + String.join(",", new TreeSet<>(e.getValue().owned())) + "\t" + e.getValue().equipped()));
            Files.write(temp, rows, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            states = Map.copyOf(next);
        } finally { Files.deleteIfExists(temp); }
    }
}
