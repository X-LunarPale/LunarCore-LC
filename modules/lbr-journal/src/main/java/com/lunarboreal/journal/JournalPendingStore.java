package com.lunarboreal.journal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A bounded retry snapshot, replayed idempotently after an interrupted shutdown. */
final class JournalPendingStore {

    static final int CAPACITY = 10_000;
    private static final int FORMAT = 0x4c4a0001;
    private final Path file;

    JournalPendingStore(Path file) { this.file = file; }

    synchronized List<JournalEntry> load() throws IOException {
        if (!Files.exists(file)) return List.of();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != FORMAT) throw new IOException("Unknown journal pending format");
            int size = input.readInt();
            if (size < 0 || size > CAPACITY) throw new IOException("Invalid journal pending size");
            List<JournalEntry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                UUID uuid = new UUID(input.readLong(), input.readLong());
                entries.add(new JournalEntry(uuid, input.readUTF(), JournalEntry.Category.parse(input.readUTF()),
                        input.readUTF(), input.readUTF(), input.readUTF(), input.readLong()));
            }
            if (input.read() != -1) throw new IOException("Unexpected journal pending data");
            return List.copyOf(entries);
        } catch (EOFException | IllegalArgumentException error) {
            throw new IOException("Invalid journal pending data", error);
        }
    }

    synchronized void save(List<JournalEntry> entries) throws IOException {
        if (entries.size() > CAPACITY) throw new IOException("Too many pending entries");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "journal-pending-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(FORMAT);
                output.writeInt(entries.size());
                for (JournalEntry entry : entries) {
                    output.writeLong(entry.uuid().getMostSignificantBits());
                    output.writeLong(entry.uuid().getLeastSignificantBits());
                    output.writeUTF(entry.playerName());
                    output.writeUTF(entry.category().id());
                    output.writeUTF(entry.key());
                    output.writeUTF(entry.label());
                    output.writeUTF(entry.world());
                    output.writeLong(entry.firstSeen());
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
