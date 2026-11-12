package com.lunarboreal.anticheat.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GrimConfigStoreTest {
    @TempDir Path root;

    private GrimConfigStore store() { return new GrimConfigStore(root); }
    private Path config(String body) throws Exception {
        Path path = root.resolve("config.yml");
        Files.writeString(path, "# original header\nconfig-flavor: V2\nconfig-version: 11\n" + body);
        return path;
    }
    private Map<String, Object> request(String id, Map<String, ?> changes) throws Exception {
        return Map.of("fingerprint", store().read(id).get("fingerprint"), "changes", changes);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> snapshot, String key) {
        return ((List<Map<String, Object>>) snapshot.get("fields")).stream()
                .filter(field -> field.get("key").equals(key)).findFirst().orElseThrow();
    }
    private long backups(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().contains(".lc-backup-")).count();
        }
    }

    @Test void directoryListsOnlyKnownFilesAndDoesNotCreateMissingDefaults() throws Exception {
        config("alerts:\n  print-to-console: true\n");
        Files.writeString(root.resolve("custom.yml"), "secret: not-for-editor\n");
        Map<String, Object> result = store().directory();
        assertEquals(10, ((List<?>) result.get("files")).size());
        assertEquals("config", ((Map<?, ?>) result.get("selected")).get("id"));
        assertEquals(0, backups(root));
        assertFalse(Files.exists(root.resolve("database.yml")));
        assertFalse(result.toString().contains("not-for-editor"));
    }

    @Test void patchBacksUpExactBytesAndPreservesCommentsUnknownKeysAndEmptyListItems() throws Exception {
        Path file = config("alerts:\n  # keep toggle comment\n  print-to-console: true\n"
                + "client-brand:\n  ignored-clients: ['^vanilla$', '']\ncustom:\n  unknown: keep-me\n");
        byte[] before = Files.readAllBytes(file);
        Map<String, Object> saved = store().patch("config",
                request("config", Map.of("alerts.print-to-console", false)));
        assertEquals(true, saved.get("ok"));
        assertEquals(List.of("alerts.print-to-console"), saved.get("changedKeys"));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertFalse(yaml.getBoolean("alerts.print-to-console"));
        assertEquals("keep-me", yaml.getString("custom.unknown"));
        assertEquals(List.of("^vanilla$", ""), yaml.getStringList("client-brand.ignored-clients"));
        assertTrue(Files.readString(file).contains("# original header"));
        assertTrue(Files.readString(file).contains("# keep toggle comment"));
        try (var paths = Files.list(root)) {
            Path backup = paths.filter(path -> path.toString().endsWith(".bak")).findFirst().orElseThrow();
            assertArrayEquals(before, Files.readAllBytes(backup));
        }
    }

    @Test void aCommentOnlyExternalEditConflictsWithoutWritingOrCreatingBackup() throws Exception {
        Path file = config("alerts:\n  print-to-console: true\n");
        var request = request("config", Map.of("alerts.print-to-console", false));
        Files.writeString(file, Files.readString(file) + "# external change\n");
        byte[] external = Files.readAllBytes(file);
        assertEquals(409, assertThrows(HttpException.class, () -> store().patch("config", request)).status());
        assertArrayEquals(external, Files.readAllBytes(file));
        assertEquals(0, backups(root));
    }

    @Test void validatesWholePatchAndRejectsExtraReadonlyUnknownAndNullFields() throws Exception {
        Path file = config("alerts:\n  print-to-console: true\ncustom:\n  unknown: text\n");
        byte[] before = Files.readAllBytes(file);
        for (String key : List.of("config-flavor", "config-version", "custom.unknown", "new.key")) {
            var changes = new LinkedHashMap<String, Object>();
            changes.put("alerts.print-to-console", false);
            changes.put(key, "invalid");
            assertEquals(400, assertThrows(HttpException.class,
                    () -> store().patch("config", request("config", changes))).status());
        }
        var changes = new LinkedHashMap<String, Object>();
        changes.put("alerts.print-to-console", null);
        assertEquals(400, assertThrows(HttpException.class,
                () -> store().patch("config", request("config", changes))).status());
        var extra = new LinkedHashMap<>(request("config", Map.of("alerts.print-to-console", false)));
        extra.put("path", "server.properties");
        assertEquals(400, assertThrows(HttpException.class, () -> store().patch("config", extra)).status());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(0, backups(root));
    }

    @Test void validatesNumbersAndBooleanWithoutStringCoercion() throws Exception {
        config("alerts:\n  print-to-console: true\nmax-transaction-time: 60\nSimulation:\n  threshold: 0.001\n");
        for (Object value : List.of("false", 1, List.of(false))) {
            assertEquals(400, assertThrows(HttpException.class, () -> store().patch("config",
                    request("config", Map.of("alerts.print-to-console", value)))).status());
        }
        for (Object value : List.of("60", 0, 181, 2.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(400, assertThrows(HttpException.class, () -> store().patch("config",
                    request("config", Map.of("max-transaction-time", value)))).status());
        }
        store().patch("config", request("config", Map.of("max-transaction-time", 180.0,
                "Simulation.threshold", 0.0005)));
        assertEquals(180, YamlConfiguration.loadConfiguration(root.resolve("config.yml").toFile())
                .getInt("max-transaction-time"));
    }

    @Test void secretValuesAreNeverReturnedAndOmittedChangesRetainTheSavedSecret() throws Exception {
        Path file = root.resolve("discord.yml");
        String privateUrl = "https://discord.com/api/webhooks/123/private-token";
        Files.writeString(file, "config-flavor: V2\nconfig-version: 1\nenabled: false\nwebhook: "
                + privateUrl + "\n");
        var read = store().read("discord");
        assertFalse(read.toString().contains(privateUrl));
        assertEquals(true, metadata(read, "webhook").get("configured"));
        var saved = store().patch("discord", request("discord", Map.of("enabled", true)));
        assertFalse(saved.toString().contains(privateUrl));
        assertEquals(privateUrl, YamlConfiguration.loadConfiguration(file.toFile()).getString("webhook"));
        var cleared = store().patch("discord", request("discord", Map.of("webhook", "")));
        assertEquals("", YamlConfiguration.loadConfiguration(file.toFile()).getString("webhook"));
        assertEquals(false, metadata((Map<String, Object>) cleared.get("snapshot"), "webhook").get("configured"));
    }

    @Test void punishmentListsPreserveOrderAndSupportOneTimeAndLargerRepeatIntervals() throws Exception {
        Path file = root.resolve("punishments.yml");
        Files.writeString(file, "# custom group\nPunishments:\n  MyMovement:\n"
                + "    remove-violations-after: 300\n    checks: ['Simulation', '!BadPacketsN']\n"
                + "    commands: ['100:40 [alert]', '1:1 [log]']\n");
        String key = "Punishments.MyMovement.commands";
        List<String> rules = List.of("100:0 kick %player% test", "20:40 [alert]");
        store().patch("punishments", request("punishments", Map.of(key, rules)));
        assertEquals(rules, YamlConfiguration.loadConfiguration(file.toFile()).getStringList(key));
        for (String rule : List.of("-1:1 [alert]", "x:1 kick test", "1:1 say one\nstop", "1:1 ")) {
            assertEquals(400, assertThrows(HttpException.class, () -> store().patch("punishments",
                    request("punishments", Map.of(key, List.of(rule))))).status());
        }
    }

    @Test void rejectsTraversalBeforeFilesystemLookupAndDoesNotReturnBadYamlSecrets() throws Exception {
        config("alerts:\n  print-to-console: true\n");
        for (String id : List.of("../config", "config.yml", "database-../../server", "CONFIG", "C:/file", ""))
            assertEquals(400, assertThrows(HttpException.class, () -> store().read(id)).status());
        assertEquals(404, assertThrows(HttpException.class, () -> store().read("database")).status());
        Files.writeString(root.resolve("discord.yml"), "webhook: [secret-leak\n");
        HttpException error = assertThrows(HttpException.class, () -> store().read("discord"));
        assertEquals(409, error.status());
        assertFalse(error.getMessage().contains("secret-leak"));
        assertNull(error.getCause());
    }

    @Test void backupsStayBoundedAndNoOpChangesDoNotCreateMore() throws Exception {
        config("alerts:\n  print-to-console: true\n");
        for (int index = 0; index < 9; index++) {
            store().patch("config", request("config", Map.of("alerts.print-to-console", index % 2 != 0)));
        }
        assertEquals(5, backups(root));
        var snapshot = store().read("config");
        var result = store().patch("config",
                Map.of("fingerprint", snapshot.get("fingerprint"), "changes", Map.of("alerts.print-to-console", false)));
        assertEquals(List.of(), result.get("changedKeys"));
        assertEquals(snapshot.get("fingerprint"), ((Map<?, ?>) result.get("snapshot")).get("fingerprint"));
        assertEquals(5, backups(root));
    }

    @Test void rejectsOversizedFilesAndListsBeforeAnyReplacement() throws Exception {
        config("client-brand:\n  ignored-clients: ['^vanilla$']\n");
        List<String> many = java.util.Collections.nCopies(513, "^vanilla$");
        assertEquals(400, assertThrows(HttpException.class, () -> store().patch("config",
                request("config", Map.of("client-brand.ignored-clients", many)))).status());
        assertEquals(0, backups(root));
        Files.writeString(root.resolve("config.yml"), "#".repeat(512 * 1024 + 1));
        assertEquals(400, assertThrows(HttpException.class, () -> store().read("config")).status());
    }

    @Test void rejectsSymlinkedFilesWhereThePlatformAllowsCreatingThem() throws Exception {
        config("alerts:\n  print-to-console: true\n");
        Path outside = Files.createTempFile("grim-config-outside-", ".yml");
        try {
            try { Files.createSymbolicLink(root.resolve("discord.yml"), outside); }
            catch (UnsupportedOperationException | java.io.IOException cause) {
                assumeTrue(false, "Symbolic links are unavailable on this platform");
            }
            assertEquals(400, assertThrows(HttpException.class, () -> store().read("discord")).status());
        } finally { Files.deleteIfExists(outside); }
    }
}
