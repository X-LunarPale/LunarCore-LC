package com.lunarboreal.scoreboard;

import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IslandPackTest {
    @Test void signedBinaryOffsetsHaveExactAdvance() {
        for (int value=-1023;value<=1023;value++) {
            int actual=IslandPack.space(value).codePoints().map(cp-> cp>=0xE110 ? 1 << (cp-0xE110) : -(1 << (cp-0xE100))).sum();
            assertEquals(value,actual);
        }
        assertThrows(IllegalArgumentException.class,()->IslandPack.space(1024));
    }
    private Map<Integer,Integer> widths() {
        Map<Integer,Integer> map = new HashMap<>(); for(int cp=32;cp<=126;cp++) map.put(cp,4);
        map.put(32,3); map.put((int)'中',9); map.put(0x25A1,9); return map;
    }
    @Test void codepointAndPixelBudgetsUseActualWidths() {
        var widths=widths();
        assertEquals("中A□",IslandPack.plain("中A😀",widths));
        assertEquals(18,IslandPack.plain("中".repeat(30),widths).length());
        assertEquals(32,IslandPack.plain("A".repeat(50),widths).length());
        assertEquals("□",IslandPack.plain("\n",widths));
        assertThrows(IllegalArgumentException.class,()->IslandPack.plain("a",Map.of()));
        for(int width=0;width<=168;width++) {int left=(192-width)/2,right=192-width-left;assertEquals(192,97-1+97-193+left+width+right);}
    }
    @Test void loadsAndRejectsInvalidGlyphMetadata() throws Exception {
        var input=new java.io.ByteArrayInputStream("0020=4\n0041=6\n25A1=17\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(6,IslandPack.readWidths(input).get(65));
        assertThrows(java.io.IOException.class,()->IslandPack.readWidths(new java.io.ByteArrayInputStream("0041=6".getBytes())));
    }
    @Test void wrapsOnlyPlainWhiteUnstyledTextAndNoShadow() {
        var wrapped=IslandPack.wrap(Component.text("Hello").decorate(TextDecoration.BOLD), widths());
        assertEquals(TextDecoration.State.FALSE,wrapped.decoration(TextDecoration.BOLD));
        assertEquals(0,wrapped.shadowColor().alpha());
        String sequence=PlainTextComponentSerializer.plainText().serialize(wrapped);
        assertTrue(sequence.contains("Hello"));
        assertTrue(sequence.indexOf('\uE000') < sequence.indexOf('\uE002'));
        assertEquals(IslandPack.space(-1),PlainTextComponentSerializer.plainText().serialize(wrapped.children().get(1)));
        assertNull(wrapped.clickEvent());
    }
    @Test void filtersOtherPackEventsAndUnsolicitedPlayers() {
        UUID player=UUID.randomUUID();
        assertTrue(IslandPack.accepts(IslandPack.ID,player,Set.of(player)));
        assertFalse(IslandPack.accepts(UUID.randomUUID(),player,Set.of(player)));
        assertFalse(IslandPack.accepts(IslandPack.ID,player,Set.of()));
    }
    @Test void validatesIndependentPackSettings() {
        assertThrows(IllegalArgumentException.class,()->new IslandPack.Settings(true,true,"file:///x"));
        assertThrows(IllegalArgumentException.class,()->new IslandPack.Settings(true,true,"https://user:password@example.com/a.zip"));
        assertThrows(IllegalArgumentException.class,()->IslandPack.Settings.from(Map.of("enabled","true","autoOffer",true,"url","https://example.com/pack.zip")));
        assertDoesNotThrow(()->new IslandPack.Settings(true,true,"https://example.com/pack.zip"));
    }
}
