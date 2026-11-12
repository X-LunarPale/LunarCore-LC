package com.lunarboreal.core.module;

import com.lunarboreal.api.web.HttpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModuleMarketTest {
    private static final String VERSION = "0.0.6-preview";
    @TempDir Path directory;

    @Test void usesPublicReleaseDownloadWithoutApiOrCredentials() {
        var module = new ModuleMarket.Entry("motd", "", "", "", "", "server", "server", List.of(),
                VERSION, "lc-motd.jar", "0".repeat(64), 1);
        var uri = ModuleMarket.downloadUri(module);
        assertEquals("https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lc-motd.jar",
                uri.toString());
        assertNull(uri.getUserInfo());
        assertNull(uri.getQuery());
    }

    private byte[] jar(String id, String version, String... dependencies) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry("module.yml"));
            jar.write(("id: " + id + "\nversion: " + version + "\nmain: example.Module\ndepends: ["
                    + String.join(", ", dependencies) + "]\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private ModuleMarket.Entry entry(String id, byte[] jar, String... dependencies) {
        return new ModuleMarket.Entry(id, id, id, "", "", "server", "package", List.of(dependencies),
                VERSION, "lc-" + id + ".jar", ModuleMarket.hash(jar), jar.length);
    }

    private void finished(ModuleMarket market) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (market.snapshot().get("state").equals("downloading")) Thread.sleep(10);
        });
    }

    @Test void stagesDependencyTogetherAndAppliesOnlyAfterRestart() throws Exception {
        byte[] land = jar("land", VERSION);
        byte[] landmarks = jar("landmarks", VERSION, "land");
        var catalog = List.of(entry("land", land), entry("landmarks", landmarks, "land"));
        try (var market = new ModuleMarket(directory, VERSION, catalog, e -> e.id().equals("land") ? land : landmarks)) {
            market.install("landmarks");
            finished(market);
            assertEquals("ready", market.snapshot().get("state"));
            assertFalse(Files.exists(directory.resolve("modules/lc-land.jar")));
            assertEquals(2, ((List<?>) market.snapshot().get("pending")).size());
            assertThrows(HttpException.class, () -> market.install("land"));
        }
        try (var restarted = new ModuleMarket(directory, VERSION, catalog, e -> fail())) {
            restarted.applyPending();
            assertArrayEquals(land, Files.readAllBytes(directory.resolve("modules/lc-land.jar")));
            assertArrayEquals(landmarks, Files.readAllBytes(directory.resolve("modules/lc-landmarks.jar")));
            assertTrue(((List<?>) restarted.snapshot().get("pending")).isEmpty());
        }
    }

    @Test void failedSecondDownloadLeavesExistingModulesAndPlanUntouched() throws Exception {
        byte[] land = jar("land", VERSION);
        byte[] landmarks = jar("landmarks", VERSION, "land");
        byte[] old = jar("land", "alpha2");
        Files.createDirectories(directory.resolve("modules"));
        Files.write(directory.resolve("modules/lc-land.jar"), old);
        try (var market = new ModuleMarket(directory, VERSION,
                List.of(entry("land", land), entry("landmarks", landmarks, "land")),
                e -> e.id().equals("land") ? land : new byte[]{1})) {
            market.install("landmarks");
            finished(market);
            assertEquals("failed", market.snapshot().get("state"));
            assertArrayEquals(old, Files.readAllBytes(directory.resolve("modules/lc-land.jar")));
            assertFalse(Files.exists(directory.resolve(".market/pending.json")));
        }
    }

    @Test void rejectsWrongModuleDescriptorEvenWhenHashMatches() throws Exception {
        byte[] wrong = jar("different", VERSION);
        try (var market = new ModuleMarket(directory, VERSION, List.of(entry("chat", wrong)), e -> wrong)) {
            market.install("chat");
            finished(market);
            assertEquals("failed", market.snapshot().get("state"));
            assertFalse(Files.exists(directory.resolve(".market/pending.json")));
        }
    }

    @Test void uninstallChecksExternalDependentsAndKeepsData() throws Exception {
        Files.createDirectories(directory.resolve("modules"));
        Files.write(directory.resolve("modules/custom-land.jar"), jar("land", VERSION));
        Files.write(directory.resolve("modules/third-party.jar"), jar("external", "1", "land"));
        Files.createDirectories(directory.resolve("modules/land"));
        Files.writeString(directory.resolve("modules/land/config.yml"), "keep");
        try (var market = new ModuleMarket(directory, VERSION, List.of(), e -> fail())) {
            assertThrows(HttpException.class, () -> market.remove("essentials"));
            assertThrows(HttpException.class, () -> market.remove("land"));
            market.remove("external");
            market.applyPending();
            assertFalse(Files.exists(directory.resolve("modules/third-party.jar")));
            market.remove("land");
            market.applyPending();
            assertEquals("keep", Files.readString(directory.resolve("modules/land/config.yml")));
        }
    }

    @Test void cancelPreservesInstallationAndAllowsRetry() throws Exception {
        byte[] bytes = jar("chat", VERSION);
        try (var market = new ModuleMarket(directory, VERSION, List.of(entry("chat", bytes)), e -> bytes)) {
            market.install("chat");
            finished(market);
            market.cancel();
            market.applyPending();
            assertFalse(Files.exists(directory.resolve("modules/lc-chat.jar")));
            market.install("chat");
            finished(market);
            market.applyPending();
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("modules/lc-chat.jar")));
            assertThrows(HttpException.class, () -> market.install("chat"));
        }
    }

    @Test void rejectsConcurrentTasksAndHidesUpstreamSecrets() throws Exception {
        byte[] bytes = jar("chat", VERSION);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var market = new ModuleMarket(directory, VERSION, List.of(entry("chat", bytes)), e -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            throw new IOException("secret-token-and-private-url");
        })) {
            market.install("chat");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(HttpException.class, () -> market.install("chat"));
            assertThrows(HttpException.class, market::cancel);
            release.countDown();
            finished(market);
            assertFalse(market.snapshot().toString().contains("secret-token"));
        }
    }

    @Test void rejectsTamperedStagedBytesBeforeChangingFiles() throws Exception {
        byte[] bytes = jar("chat", VERSION);
        byte[] old = jar("chat", "alpha2");
        Files.createDirectories(directory.resolve("modules"));
        Files.write(directory.resolve("modules/custom.jar"), old);
        try (var market = new ModuleMarket(directory, VERSION, List.of(entry("chat", bytes)), e -> bytes)) {
            market.install("chat");
            finished(market);
            Files.write(directory.resolve(".market/staged/lc-chat.jar"), new byte[]{1});
            assertThrows(IOException.class, market::applyPending);
            assertArrayEquals(old, Files.readAllBytes(directory.resolve("modules/custom.jar")));
        }
    }

    @Test void interruptedApplyRestoresBackupBeforeRetryAndClearsCommitMarker() throws Exception {
        byte[] bytes = jar("chat", VERSION);
        byte[] old = jar("chat", "alpha2");
        Files.createDirectories(directory.resolve("modules"));
        Files.write(directory.resolve("modules/custom.jar"), old);
        var catalog = List.of(entry("chat", bytes));
        try (var market = new ModuleMarket(directory, VERSION, catalog, e -> bytes)) {
            market.install("chat");
            finished(market);
        }
        Files.copy(directory.resolve("modules/custom.jar"), directory.resolve(".market/backup/custom.jar"));
        Files.writeString(directory.resolve(".market/applying"), VERSION);
        Files.write(directory.resolve("modules/custom.jar"), bytes);
        Files.write(directory.resolve(".market/staged/lc-chat.jar"), new byte[]{1});
        try (var market = new ModuleMarket(directory, VERSION, catalog, e -> bytes)) {
            assertThrows(IOException.class, market::applyPending);
            assertArrayEquals(old, Files.readAllBytes(directory.resolve("modules/custom.jar")));
            market.cancel();
            Files.writeString(directory.resolve(".market/committed"), VERSION);
            market.applyPending();
            assertFalse(Files.exists(directory.resolve(".market/committed")));
            market.install("chat");
            finished(market);
            market.applyPending();
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("modules/custom.jar")));
        }
    }

    @Test void rejectsTraversalAndVersionMismatchInPersistedPlan() throws Exception {
        try (var market = new ModuleMarket(directory, VERSION, List.of(), e -> fail())) {
            Path plan = directory.resolve(".market/pending.json");
            Files.writeString(plan, "{\"version\":\"" + VERSION + "\",\"changes\":[{\"id\":\"chat\",\"file\":\"../escape.jar\",\"existed\":false}]}");
            assertThrows(IOException.class, market::applyPending);
            Files.writeString(plan, "{\"version\":\"alpha2\",\"changes\":[]}");
            assertThrows(IOException.class, market::applyPending);
            assertFalse(Files.exists(directory.resolve("escape.jar")));
        }
    }

    @Test void dependencyCyclesFailBeforeStartingDownload() throws Exception {
        byte[] a = jar("a", VERSION, "b");
        byte[] b = jar("b", VERSION, "a");
        try (var market = new ModuleMarket(directory, VERSION, List.of(entry("a", a, "b"), entry("b", b, "a")), e -> fail())) {
            assertThrows(HttpException.class, () -> market.install("a"));
            assertEquals("idle", market.snapshot().get("state"));
        }
    }

    @Test void partialApplyFailureRollsBackAllModules() throws Exception {
        byte[] land = jar("land", VERSION);
        byte[] landmarks = jar("landmarks", VERSION, "land");
        try (var market = new ModuleMarket(directory, VERSION,
                List.of(entry("land", land), entry("landmarks", landmarks, "land")),
                e -> e.id().equals("land") ? land : landmarks)) {
            market.install("landmarks");
            finished(market);
            Path obstruction = directory.resolve("modules/lc-landmarks.jar.market-tmp");
            Files.createDirectory(obstruction);
            Files.writeString(obstruction.resolve("block"), "keep");
            assertThrows(IOException.class, market::applyPending);
            assertFalse(Files.exists(directory.resolve("modules/lc-land.jar")));
            assertFalse(Files.exists(directory.resolve("modules/lc-landmarks.jar")));
            assertTrue(Files.exists(directory.resolve(".market/pending.json")));
        }
    }
}
