package com.lunarboreal.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JournalRepositoryTest {

    @TempDir Path directory;
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    private JournalRepository repository() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("journal.db");
        JournalRepository repository = new JournalRepository(() -> DriverManager.getConnection(url), "test_journal");
        repository.init();
        return repository;
    }

    private JournalEntry entry(UUID uuid, String name, JournalEntry.Category category, String key, long time) {
        return new JournalEntry(uuid, name, category, key, JournalEntry.readableKey(key), "world", time);
    }

    @Test void rediscoveryAfterRestartKeepsOriginalTimeAndWorld() throws Exception {
        var repository = repository();
        var original = entry(first, "Alice", JournalEntry.Category.BIOMES, "minecraft:plains", 100);
        assertTrue(repository.save(original).inserted());
        var restarted = repository();
        var rediscovered = new JournalEntry(first, "NewName", original.category(), original.key(), "Changed", "world_nether", 900);
        var saved = restarted.save(rediscovered);
        assertFalse(saved.inserted());
        assertEquals(original, saved.entry());
        assertEquals(1, restarted.stats(null).total());
    }

    @Test void categoriesAndPlayersHaveIndependentCollections() throws Exception {
        var repository = repository();
        repository.save(entry(first, "Alice", JournalEntry.Category.BIOMES, "minecraft:cod", 1));
        repository.save(entry(first, "Alice", JournalEntry.Category.FISHING, "minecraft:cod", 2));
        repository.save(entry(second, "Bob", JournalEntry.Category.FISHING, "minecraft:cod", 3));
        assertEquals(new JournalRepository.Stats(3, 2, 1, 0, 2), repository.stats(null));
        assertEquals(new JournalRepository.Stats(2, 1, 1, 0, 1), repository.stats(first));
        assertEquals(1, repository.page(first, JournalEntry.Category.FISHING, "", 0, 20).total());
    }

    @Test void paginationSearchAndWildcardsRemainLiteral() throws Exception {
        var repository = repository();
        repository.save(entry(first, "Alice", JournalEntry.Category.ADVANCEMENTS, "minecraft:story/mine_stone", 10));
        repository.save(entry(first, "Alice", JournalEntry.Category.FISHING, "minecraft:cod", 20));
        repository.save(entry(second, "Bob", JournalEntry.Category.BIOMES, "minecraft:plains", 30));
        var page = repository.page(null, null, "", 1, 1);
        assertEquals(3, page.total());
        assertEquals("minecraft:cod", page.entries().getFirst().key());
        assertEquals(2, repository.page(null, null, "Alice", 0, 20).total());
        assertEquals(0, repository.page(null, null, "%", 0, 20).total());
        assertEquals(1, repository.page(null, null, "_", 0, 20).total());
        assertEquals(0, repository.page(null, null, "' OR 1=1 --", 0, 20).total());
        assertEquals(0, repository.page(null, null, "", 500, 20).entries().size());
    }
}
