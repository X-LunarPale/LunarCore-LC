package com.lunarboreal.titles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TitleDefinitionsTest {
    @TempDir Path directory;
    @Test void customCreateUpdateDeleteSurvivesDiskAndRetainsOtherConfig() throws Exception {
        Path path=directory.resolve("config.yml"); Files.writeString(path,"other: retained\n");
        var first=new TitleDefinition("custom", "<gold>极光 守望者</gold>", "");
        var definitions=TitleDefinitions.save(path,Map.of(),"create",first,"custom");
        var disk=YamlConfiguration.loadConfiguration(path.toFile());
        assertEquals("retained",disk.getString("other"));
        assertEquals(first.display(),disk.getMapList("titles").getFirst().get("display"));
        assertThrows(IllegalArgumentException.class,()->TitleDefinitions.save(path,definitions,"create",first,"custom"));
        var updated=TitleDefinitions.save(path,definitions,"update",new TitleDefinition("custom","<aqua>新版</aqua>",""),"custom");
        assertEquals("<aqua>新版</aqua>",YamlConfiguration.loadConfiguration(path.toFile()).getMapList("titles").getFirst().get("display"));
        var removed=TitleDefinitions.save(path,updated,"delete",null,"custom");
        assertTrue(removed.isEmpty());
        assertTrue(YamlConfiguration.loadConfiguration(path.toFile()).getMapList("titles").isEmpty());
    }
    @Test void failedPersistenceDoesNotMutatePreviousDefinitions() throws Exception {
        var original=Map.of("old",new TitleDefinition("old","旧称号",""));
        Path parent=directory.resolve("blocked");Files.writeString(parent,"file");
        assertThrows(java.io.IOException.class,()->TitleDefinitions.save(parent.resolve("config.yml"),original,"create",new TitleDefinition("new","新称号",""),"new"));
        assertEquals(Set.of("old"),original.keySet());
    }
}
