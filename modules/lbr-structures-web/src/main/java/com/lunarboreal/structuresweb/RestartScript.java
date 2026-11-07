package com.lunarboreal.structuresweb;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Matches Paper 26.3's first-space-delimited script check; Paper invokes sh/cmd itself. */
final class RestartScript {
    private RestartScript() {}

    static boolean available(String command) {
        if (command == null || command.isBlank() || command.chars().anyMatch(Character::isISOControl)) return false;
        try {
            Path script = Path.of(command.split(" ", -1)[0]);
            return Files.isRegularFile(script) && Files.isReadable(script);
        } catch (InvalidPathException | SecurityException e) { return false; }
    }
}
