package com.lunarboreal.titles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TitleStoreTest {
    @TempDir Path directory;
    @Test void persistsOfflineOwnershipAndClearsRevokedEquippedTitle() throws Exception {
        UUID id = UUID.randomUUID(); Path file = directory.resolve("players.tsv");
        TitleStore store = new TitleStore(file);
        store.change(id, "grant", "pioneer"); store.change(id, "equip", "pioneer");
        TitleStore reloaded = new TitleStore(file); reloaded.load();
        assertEquals("pioneer", reloaded.get(id).equipped());
        reloaded.change(id, "revoke", "pioneer");
        TitleStore empty = new TitleStore(file); empty.load();
        assertTrue(empty.get(id).owned().isEmpty()); assertEquals("", empty.get(id).equipped());
    }
    @Test void refusesUnownedEquipAndCorruptState() throws Exception {
        TitleStore store = new TitleStore(directory.resolve("players.tsv"));
        assertThrows(IllegalArgumentException.class, () -> store.change(UUID.randomUUID(), "equip", "pioneer"));
        Files.writeString(directory.resolve("players.tsv"), "broken");
        assertThrows(java.io.IOException.class, store::load);
    }
    @Test void failedWriteDoesNotPublishNewState() throws Exception {
        Path blocked = directory.resolve("blocked"); Files.writeString(blocked, "file");
        UUID id = UUID.randomUUID(); TitleStore store = new TitleStore(blocked.resolve("players.tsv"));
        assertThrows(java.io.IOException.class, () -> store.change(id, "grant", "pioneer"));
        assertTrue(store.get(id).owned().isEmpty());
    }
    @Test void definitionsRejectInjectionIdsAndStripClickEvents() {
        assertThrows(IllegalArgumentException.class, () -> new TitleDefinition("../oops", "hello", ""));
        TitleDefinition title = new TitleDefinition("safe", "<click:run_command:'/op a'><gold>Hello</gold></click>", "");
        assertNull(title.component().clickEvent());
        assertNull(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(title.safeMini()).clickEvent());
        assertTrue(title.plain().contains("Hello"));
    }
}
