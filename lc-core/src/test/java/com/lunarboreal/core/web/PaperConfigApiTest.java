package com.lunarboreal.core.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PaperConfigApiTest {
    @TempDir Path root;

    private Path config(String scope, String body) throws Exception {
        Path directory = Files.createDirectories(root.resolve("config"));
        Path file = directory.resolve(scope.equals("global") ? "paper-global.yml" : "paper-world-defaults.yml");
        Files.writeString(file, "# original header\n_version: 31\n" + body);
        return file;
    }

    private Map<String, Object> request(PaperConfigApi api, String scope, Map<String, ?> patch) throws Exception {
        return Map.of("fingerprint", api.read(scope).get("fingerprint"), "patch", patch);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> field(Map<String, Object> response, String key) {
        return ((List<Map<String, Object>>) response.get("fields")).stream()
                .filter(f -> key.equals(f.get("key"))).findFirst().orElseThrow();
    }

    @Test void readsRealValuesBaselineAndReadOnlyUnknownNamesWithoutSecrets() throws Exception {
        config("global", "collisions:\n  enable-player-collisions: false\nproxies:\n  velocity:\n    secret: top-secret\ncustom:\n  feature: true\n");
        Map<String, Object> result = new PaperConfigApi(root).read("global");
        assertEquals(false, ((Map<?, ?>) result.get("values")).get("collisions.enable-player-collisions"));
        Map<String, Object> collision = field(result, "collisions.enable-player-collisions");
        assertEquals(true, collision.get("defaultValue"));
        assertEquals(true, collision.get("editable"));
        assertFalse((Boolean) field(result, "spark.enabled").get("present"));
        assertFalse((Boolean) field(result, "spark.enabled").get("editable"));
        assertTrue(((List<?>) result.get("unknownKeys")).contains("proxies.velocity.secret"));
        assertFalse(result.toString().contains("top-secret"));
        assertTrue(result.get("fingerprint").toString().matches("sha256:[a-f0-9]{64}"));
    }

    @Test void savesPatchBacksUpExactBytesAndPreservesUnknownYaml() throws Exception {
        Path file = config("global", "collisions:\n  enable-player-collisions: true\ncustom:\n  unknown-list: [one, two]\nproxies:\n  velocity:\n    secret: unchanged\n");
        byte[] before = Files.readAllBytes(file);
        PaperConfigApi api = new PaperConfigApi(root);
        Map<String, Object> request = request(api, "global", Map.of("collisions.enable-player-collisions", false));
        Map<String, Object> result = api.patch("global", request);
        assertTrue((Boolean) result.get("restartRequired"));
        assertEquals(List.of("collisions.enable-player-collisions"), result.get("changedKeys"));
        assertNotEquals(request.get("fingerprint"), result.get("fingerprint"));
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file.toFile());
        assertFalse(saved.getBoolean("collisions.enable-player-collisions"));
        assertEquals(List.of("one", "two"), saved.getStringList("custom.unknown-list"));
        assertEquals("unchanged", saved.getString("proxies.velocity.secret"));
        try (var paths = Files.list(file.getParent())) {
            List<Path> backups = paths.filter(p -> p.getFileName().toString().contains(".lc-backup-")).toList();
            assertEquals(1, backups.size());
            assertArrayEquals(before, Files.readAllBytes(backups.getFirst()));
        }
        assertEquals(409, assertThrows(HttpException.class, () -> api.patch("global", request)).status());
    }

    @Test void externalChangesIncludingCommentOnlyEditsConflict() throws Exception {
        Path file = config("global", "spark:\n  enabled: true\n");
        PaperConfigApi api = new PaperConfigApi(root);
        Map<String, Object> request = request(api, "global", Map.of("spark.enabled", false));
        Files.writeString(file, Files.readString(file) + "# externally changed\n");
        byte[] external = Files.readAllBytes(file);
        assertEquals(409, assertThrows(HttpException.class, () -> api.patch("global", request)).status());
        assertArrayEquals(external, Files.readAllBytes(file));
        assertEquals(1, countFiles(file.getParent()));
    }

    @Test void validatesEntirePatchBeforeAnyWritesAndRejectsUnknownKeys() throws Exception {
        Path file = config("global", "spark:\n  enabled: true\n");
        PaperConfigApi api = new PaperConfigApi(root);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("spark.enabled", false);
        patch.put("proxies.velocity.secret", "injected");
        byte[] before = Files.readAllBytes(file);
        assertEquals(400, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", patch))).status());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(1, countFiles(file.getParent()));
    }

    @Test void strictBooleanNumericAndEnumValidation() throws Exception {
        Path file = config("global", "spark:\n  enabled: true\nmisc:\n  max-joins-per-tick: 5\npacket-limiter:\n  all-packets:\n    action: KICK\n    interval: 7.0\n");
        PaperConfigApi api = new PaperConfigApi(root);
        for (Object bad : List.of("false", 0, List.of(false), Map.of("value", false)))
            assertEquals(400, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", Map.of("spark.enabled", bad)))).status());
        for (Object bad : List.of("5", 1.5, 0, 1001, Double.NaN, Double.POSITIVE_INFINITY, true))
            assertEquals(400, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", Map.of("misc.max-joins-per-tick", bad)))).status());
        for (Object bad : List.of("kick", "EXEC", 1))
            assertEquals(400, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", Map.of("packet-limiter.all-packets.action", bad)))).status());
        api.patch("global", request(api, "global", Map.of("packet-limiter.all-packets.action", "DROP", "packet-limiter.all-packets.interval", 0.5)));
        assertEquals("DROP", YamlConfiguration.loadConfiguration(file.toFile()).getString("packet-limiter.all-packets.action"));
    }

    @Test void acceptsDocumentedSentinelsButRejectsNumericStringsAndFractions() throws Exception {
        config("global", "misc:\n  compression-level: default\nchunk-system:\n  io-threads: -1\n");
        PaperConfigApi api = new PaperConfigApi(root);
        api.patch("global", request(api, "global", Map.of("misc.compression-level", 4, "chunk-system.io-threads", 2)));
        api.patch("global", request(api, "global", Map.of("misc.compression-level", "default", "chunk-system.io-threads", -1)));
        for (Object bad : List.of("-1", -0.5, -2, 0))
            assertEquals(400, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", Map.of("chunk-system.io-threads", bad)))).status());
    }

    @Test void deniesTraversalPathsExtraBodyFieldsAndNullDeletion() throws Exception {
        config("global", "spark:\n  enabled: true\n");
        PaperConfigApi api = new PaperConfigApi(root);
        for (String scope : List.of("../global", "world", "global/../../server.properties", "E:/file", "world-defaults/..", "GLOBAL"))
            assertEquals(400, assertThrows(HttpException.class, () -> api.read(scope)).status());
        assertEquals(404, assertThrows(HttpException.class, () -> api.read("world-defaults")).status());
        Map<String, Object> extra = new LinkedHashMap<>(request(api, "global", Map.of("spark.enabled", false)));
        extra.put("path", "server.properties");
        assertEquals(400, assertThrows(HttpException.class, () -> api.patch("global", extra)).status());
        Map<String, Object> deletion = new LinkedHashMap<>();
        deletion.put("spark.enabled", null);
        assertEquals(400, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", deletion))).status());
        assertEquals(400, assertThrows(HttpException.class, () -> api.patch("global", Map.of("fingerprint", "invalid", "patch", Map.of("spark.enabled", false)))).status());
    }

    @Test void missingOrIncompatibleFieldsAndFutureVersionsAreReadOnly() throws Exception {
        Path file = config("global", "spark:\n  enabled: 'true'\n");
        PaperConfigApi api = new PaperConfigApi(root);
        assertEquals(false, field(api.read("global"), "spark.enabled").get("editable"));
        assertEquals(409, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", Map.of("spark.enabled", false)))).status());
        assertEquals(409, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", Map.of("misc.enable-nether", false)))).status());
        Files.writeString(file, "_version: 32\nspark:\n  enabled: true\n");
        assertEquals(false, field(api.read("global"), "spark.enabled").get("editable"));
        assertEquals(409, assertThrows(HttpException.class, () -> api.patch("global", request(api, "global", Map.of("spark.enabled", false)))).status());
    }

    @Test void minimumMaximumPairsMustRemainOrdered() throws Exception {
        Path file = config("world-defaults", "fishing-time-range:\n  minimum: 100\n  maximum: 600\n");
        PaperConfigApi api = new PaperConfigApi(root);
        byte[] before = Files.readAllBytes(file);
        assertEquals(400, assertThrows(HttpException.class, () -> api.patch("world-defaults", request(api, "world-defaults", Map.of("fishing-time-range.minimum", 601)))).status());
        assertArrayEquals(before, Files.readAllBytes(file));
        api.patch("world-defaults", request(api, "world-defaults", Map.of("fishing-time-range.minimum", 700, "fishing-time-range.maximum", 800)));
    }

    @Test void noOpDoesNotCreateBackupOrChangeFingerprint() throws Exception {
        Path file = config("global", "misc:\n  max-joins-per-tick: 5\n");
        PaperConfigApi api = new PaperConfigApi(root);
        Map<String, Object> request = request(api, "global", Map.of("misc.max-joins-per-tick", 5d));
        Map<String, Object> result = api.patch("global", request);
        assertEquals(request.get("fingerprint"), result.get("fingerprint"));
        assertEquals(List.of(), result.get("changedKeys"));
        assertEquals(1, countFiles(file.getParent()));
    }

    @Test void concurrentWritersWithSameFingerprintCannotBothCommit() throws Exception {
        config("global", "misc:\n  max-joins-per-tick: 5\n");
        PaperConfigApi first = new PaperConfigApi(root);
        PaperConfigApi second = new PaperConfigApi(root);
        Map<String, Object> a = request(first, "global", Map.of("misc.max-joins-per-tick", 6));
        Map<String, Object> b = request(second, "global", Map.of("misc.max-joins-per-tick", 7));
        var ready = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { ready.await(); try { first.patch("global", a); return 200; } catch (HttpException e) { return e.status(); } });
            var two = executor.submit(() -> { ready.await(); try { second.patch("global", b); return 200; } catch (HttpException e) { return e.status(); } });
            ready.countDown();
            assertEquals(Set.of(200, 409), Set.of(one.get(), two.get()));
        }
    }

    @Test void malformedYamlDoesNotAllowOverwritingOriginal() throws Exception {
        Path file = config("global", "spark: [invalid\n");
        PaperConfigApi api = new PaperConfigApi(root);
        byte[] before = Files.readAllBytes(file);
        assertEquals(409, assertThrows(HttpException.class, () -> api.read("global")).status());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    private long countFiles(Path directory) throws Exception {
        try (var paths = Files.list(directory)) { return paths.count(); }
    }
}
