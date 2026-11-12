package com.lunarboreal.core.module;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in check against the actual published GitHub asset. */
class ModuleMarketReleaseTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "LUNARCORE_MARKET_RELEASE_TEST", matches = "true")
    void downloadsPublishedModuleAndAppliesVerifiedJar() throws Exception {
        try (var market = new ModuleMarket(directory, "0.0.6-preview")) {
            market.install("motd");
            assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
                while (market.snapshot().get("state").equals("downloading")) Thread.sleep(100);
            });
            assertEquals("ready", market.snapshot().get("state"), market.snapshot().get("error").toString());
            market.applyPending();
            assertTrue(Files.isRegularFile(directory.resolve("modules/lc-motd.jar")));
        }
    }
}
