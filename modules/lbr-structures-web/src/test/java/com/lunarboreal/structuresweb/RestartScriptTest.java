package com.lunarboreal.structuresweb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RestartScriptTest {
    @TempDir Path directory;

    @Test void missingBlankDirectoryAndMalformedCommandsCannotStopTheServer() {
        assertFalse(RestartScript.available(null));
        assertFalse(RestartScript.available(" "));
        assertFalse(RestartScript.available(directory.toString()));
        assertFalse(RestartScript.available(directory.resolve("missing.sh").toString()));
        assertFalse(RestartScript.available("start.sh\nstop"));
        assertFalse(RestartScript.available("\0"));
    }

    @Test void followsPapersScriptAndArgumentsParsingAndRechecksRemoval() throws Exception {
        Path script = directory.resolve("start.sh");
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        assertTrue(RestartScript.available(script.toString()));
        assertTrue(RestartScript.available(script + " --example"));
        assertFalse(RestartScript.available(" " + script));
        Path spaced = directory.resolve("start server.sh");
        Files.copy(script, spaced);
        assertFalse(RestartScript.available(spaced.toString()));
        Files.delete(script);
        assertFalse(RestartScript.available(script.toString()));
    }
}
