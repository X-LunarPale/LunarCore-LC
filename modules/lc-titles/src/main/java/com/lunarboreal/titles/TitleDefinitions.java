package com.lunarboreal.titles;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Validate first, persist atomically, then let the caller publish the new definitions. */
final class TitleDefinitions {
    static Map<String, TitleDefinition> save(Path file, Map<String, TitleDefinition> previous,
                                              String action, TitleDefinition definition, String id) throws IOException {
        TitleDefinition.validId(id);
        Map<String, TitleDefinition> next = new LinkedHashMap<>(previous);
        switch (action) {
            case "create" -> {
                if (next.containsKey(id)) throw new IllegalArgumentException("称号已存在，请使用 update");
                next.put(id, Objects.requireNonNull(definition));
            }
            case "update" -> {
                if (!next.containsKey(id)) throw new IllegalArgumentException("未知称号，请使用 create");
                next.put(id, Objects.requireNonNull(definition));
            }
            case "save" -> next.put(id, Objects.requireNonNull(definition));
            case "delete" -> {
                if (next.remove(id) == null) throw new IllegalArgumentException("未知称号");
            }
            default -> throw new IllegalArgumentException("未知定义操作");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.exists(file)) {
            try { yaml.load(file.toFile()); }
            catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("拒绝覆盖损坏的称号配置", e); }
        }
        yaml.set("titles", next.values().stream().map(t -> Map.of("id", t.id(), "display", t.display(), "permission", t.permission())).toList());
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "definitions-", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        return Collections.unmodifiableMap(next);
    }
}
