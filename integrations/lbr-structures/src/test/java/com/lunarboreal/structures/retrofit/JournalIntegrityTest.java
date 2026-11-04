package com.lunarboreal.structures.retrofit;

import com.google.gson.Gson;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JournalIntegrityTest {
    private Path fixture(Path root) throws Exception {
        String id=UUID.randomUUID().toString();Path directory=root.resolve(id);Files.createDirectories(directory);
        var tag=new CompoundTag();tag.putInt("xPos",1);tag.putInt("zPos",2);tag.putString("evidence","original");
        Path image=directory.resolve("before_1_2.nbt");NbtIo.writeCompressed(tag,image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image)));
        Map<String,Object> record=new LinkedHashMap<>();
        record.put("schema",1);record.put("id",id);record.put("world",UUID.randomUUID().toString());record.put("worldKey","minecraft:overworld");
        record.put("candidate","fixture");record.put("status","APPLYING");record.put("bounds",List.of(16,0,32,31,20,47));
        record.put("structureIds",List.of("minecraft:desert_pyramid","betterdeserttemples:desert_temple"));
        record.put("hashes",Map.of("before_1_2.nbt",hash));
        Files.writeString(directory.resolve("journal.json"),new Gson().toJson(record));
        return directory;
    }

    @Test void loadsAnInterruptedCommitAndVerifiesEveryRollbackImage(@TempDir Path root) throws Exception {
        Path directory=fixture(root);var journal=JobJournal.load(root).getFirst();
        assertEquals("APPLYING",journal.status());
        assertEquals("original",journal.beforeImages().getFirst().getStringOr("evidence",""));
        assertEquals(16,journal.bounds().minX());
        assertTrue(journal.structureIds().contains("minecraft:desert_pyramid"));
        Files.write(directory.resolve("before_1_2.nbt"),new byte[]{1,2,3});
        assertThrows(java.io.IOException.class,journal::beforeImages);
        assertEquals("APPLYING",journal.status());
    }

    @Test void diskWriteFailureCannotPublishACommittedState(@TempDir Path root) throws Exception {
        Path directory=fixture(root);
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        var journal=JobJournal.load(root).getFirst();String original=Files.readString(directory.resolve("journal.json"));
        Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(directory,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE));
            assertThrows(java.io.IOException.class,()->journal.status("COMMITTED"));
            assertEquals("APPLYING",journal.status());
            assertEquals(original,Files.readString(directory.resolve("journal.json")));
        } finally { Files.setPosixFilePermissions(directory,permissions); }
        assertEquals("APPLYING",JobJournal.load(root).getFirst().status());
    }
}
