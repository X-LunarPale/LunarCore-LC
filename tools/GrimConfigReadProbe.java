package com.lunarboreal.anticheat.web;

import com.google.gson.GsonBuilder;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only acceptance probe. Values are validated in memory and never printed. */
public final class GrimConfigReadProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <GrimAC folder> <sanitized snapshot JSON>");
        Path root = Path.of(args[0]);
        GrimConfigStore store = new GrimConfigStore(root);
        var directory = store.directory();
        var files = (List<Map<String, Object>>) directory.get("files");
        var snapshots = new ArrayList<Map<String, Object>>();
        int total = 0, readOnly = 0, secrets = 0;
        Set<String> supported = Set.of("bool", "number", "text", "textarea", "select", "string-list", "punishment-commands");
        for (var file : files) {
            if (!Boolean.TRUE.equals(file.get("available"))) continue;
            String id = (String) file.get("id");
            String name = (String) file.get("name");
            var snapshot = store.read(id);
            var metadata = (List<Map<String, Object>>) snapshot.get("fields");
            var values = (Map<String, Object>) snapshot.get("values");
            var yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(root.resolve(name)));
            var fields = GrimConfigSchema.fields(id, yaml);
            long leaves = yaml.getKeys(true).stream().filter(key -> !yaml.isConfigurationSection(key)).count();
            if (fields.size() != leaves) throw new AssertionError(id + ": missing fields");
            for (var field : fields) {
                if (!field.label().matches(".*\\p{IsHan}.*")) throw new AssertionError(id + ": missing Chinese name for " + field.key());
                if (field.label().equals("未收录配置")) throw new AssertionError(id + ": unsupported installed key " + field.key());
                if (!field.readOnly() && !supported.contains(field.type())) throw new AssertionError("Unsupported field type: " + field.type());
                if (!field.readOnly()) field.validate(yaml.get(field.key()));
                else readOnly++;
                if (field.secret()) {
                    secrets++;
                    if (!"".equals(values.get(field.key()))) throw new AssertionError("Secret returned: " + field.key());
                }
            }
            for (var field : metadata) {
                if (id.equals("messages") && field.get("key").toString().startsWith("webhook")
                        && Boolean.TRUE.equals(field.get("secret"))) throw new AssertionError("Message masked as secret");
            }
            total += fields.size();
            snapshots.add(snapshot);
            System.out.println(id + ": " + fields.size() + " Chinese fields, default values validated");
        }
        var output = Map.of("files", files, "snapshots", snapshots);
        Path outputPath = Path.of(args[1]);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.writeString(outputPath, new GsonBuilder().setPrettyPrinting().create().toJson(output));
        System.out.println("PASS " + snapshots.size() + " files, " + total + " fields, " + readOnly
                + " readonly markers, " + secrets + " masked secrets; no server configuration writes");
    }
}
