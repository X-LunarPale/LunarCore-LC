package com.lunarboreal.scoreboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class ScoreboardTest {
    @TempDir Path dir;
    private Map<String,Object> valid() {
        Map<String,Object> map = new HashMap<>();
        map.put("enabled",true);map.put("defaultMode","sidebar");map.put("title","<aqua>Title</aqua>");
        map.put("lines","same\nsame\n");map.put("frames","one\ntwo");map.put("updateTicks",20);map.put("rotationTicks",100);map.put("rotate",true);map.put("color","BLUE");map.put("progress",0.5);
        return map;
    }
    @Test void strictWebValidationAndRoundtrip() {
        var settings = ScoreboardSettings.from(valid());
        assertEquals(List.of("same","same",""),settings.lines());
        assertEquals(settings,ScoreboardSettings.from(settings.webValues()));
        for (var invalid : List.of(Map.entry("defaultMode","default"),Map.entry("defaultMode","fake"),Map.entry("updateTicks",0),Map.entry("updateTicks",20.5),Map.entry("progress",2),Map.entry("progress",Double.NaN),Map.entry("enabled","true"),Map.entry("lines",String.join("\n",Collections.nCopies(16,"line"))))) {
            var map = valid();map.put(invalid.getKey(),invalid.getValue());assertThrows(IllegalArgumentException.class,()->ScoreboardSettings.from(map));
        }
    }
    @Test void administratorTemplateHasSeparateLimitsAndPersists() {
        var map = valid(); map.put("adminTitle", "<gold>管理"); map.put("adminLines", "{title}\n{balance}\n{entities}");
        var settings = ScoreboardSettings.from(map);
        assertEquals(List.of("{title}","{balance}","{entities}"), settings.adminLines());
        assertNotEquals(settings.lines(), settings.adminLines());
        assertEquals(settings, ScoreboardSettings.from(settings.webValues()));
        map.put("adminLines", String.join("\n", Collections.nCopies(16,"line")));
        assertThrows(IllegalArgumentException.class, () -> ScoreboardSettings.from(map));
    }
    @Test void literalDynamicVariablesCannotBecomeMiniMessageActions() {
        var text=DisplayRenderer.renderTemplate("<aqua>{player}</aqua> %test_value%",Map.of("player","<click:run_command:'/op x'>X</click>"),key->"<red>world</red>");
        assertEquals("<click:run_command:'/op x'>X</click> <red>world</red>",PlainTextComponentSerializer.plainText().serialize(text));
        assertNull(text.clickEvent());
    }
    @Test void preferencesPersistDefaultRemovesOverrideAndCorruptionRefuses() throws Exception {
        Path file=dir.resolve("players.tsv");UUID id=UUID.randomUUID();var store=new PlayerModes(file);
        store.set(id,ScoreboardSettings.Mode.ISLAND);var reload=new PlayerModes(file);reload.load();assertEquals(ScoreboardSettings.Mode.ISLAND,reload.get(id));
        reload.set(id,ScoreboardSettings.Mode.DEFAULT);var reset=new PlayerModes(file);reset.load();assertEquals(ScoreboardSettings.Mode.DEFAULT,reset.get(id));
        Files.writeString(file,"invalid");assertThrows(java.io.IOException.class,reset::load);
    }
    @Test void failedPreferenceWriteDoesNotPublish() throws Exception {
        Path blocked=dir.resolve("blocked");Files.writeString(blocked,"file");UUID id=UUID.randomUUID();var modes=new PlayerModes(blocked.resolve("player.tsv"));
        assertThrows(java.io.IOException.class,()->modes.set(id,ScoreboardSettings.Mode.OFF));assertEquals(ScoreboardSettings.Mode.DEFAULT,modes.get(id));
    }
}
