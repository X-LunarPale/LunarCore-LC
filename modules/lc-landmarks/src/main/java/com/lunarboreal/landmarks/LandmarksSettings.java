package com.lunarboreal.landmarks;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

record LandmarksSettings(boolean enabled, List<String> worlds, int limit) {
    LandmarksSettings {
        worlds = List.copyOf(worlds);
        if (worlds.size() > 64 || limit < 1 || limit > 12
                || worlds.stream().distinct().count() != worlds.size()) throw Landmark.problem("config");
        for (String world : worlds) {
            if (!Landmark.text(world, 1, 128).equals(world) || world.equals("*")) throw Landmark.problem("config");
        }
    }
    boolean allows(String world) { return enabled && worlds.contains(world); }
    Map<String, Object> web() {
        return Map.of("enabled", enabled, "worlds", String.join("\n", worlds), "max-per-player", limit);
    }
    static LandmarksSettings fromWeb(Map<String, Object> body) {
        if (body == null || !body.keySet().equals(Set.of("enabled", "worlds", "max-per-player"))
                || !(body.get("enabled") instanceof Boolean enabled)
                || !(body.get("worlds") instanceof String value) || value.length() > 8192)
            throw Landmark.problem("config");
        List<String> worlds = new ArrayList<>();
        for (String line : value.split("\\R", -1)) if (!line.isBlank()) worlds.add(line.strip());
        return new LandmarksSettings(enabled, worlds, integer(body.get("max-per-player")));
    }
    static LandmarksSettings load(Path file) throws Exception {
        if (Files.size(file) > 65_536) throw new IOException("Landmark configuration exceeds size limit");
        var yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> values = yaml.getValues(false);
        if (!values.keySet().equals(Set.of("enabled", "worlds", "max-per-player"))
                || !(values.get("enabled") instanceof Boolean enabled)
                || !(values.get("worlds") instanceof List<?> list)
                || list.stream().anyMatch(value -> !(value instanceof String))) throw Landmark.problem("config");
        return new LandmarksSettings(enabled, list.stream().map(String.class::cast).toList(),
                integer(values.get("max-per-player")));
    }
    void save(Path file) throws IOException {
        var yaml = new YamlConfiguration();
        yaml.set("enabled", enabled);
        yaml.set("worlds", worlds);
        yaml.set("max-per-player", limit);
        LandmarkStore.atomicWrite(file, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
    }
    private static int integer(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 1 || n.doubleValue() > 12)
            throw Landmark.problem("config");
        return n.intValue();
    }
}
