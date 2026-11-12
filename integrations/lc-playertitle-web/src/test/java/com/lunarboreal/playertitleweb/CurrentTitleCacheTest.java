package com.lunarboreal.playertitleweb;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CurrentTitleCacheTest {
    private Map<String,Object> row(String text,boolean equipped,long expiry) {
        return Map.of("titleName",text,"isUseShow",equipped,"expirationTime",expiry);
    }
    @Test void onlyEquippedUnexpiredTitleIsSelected() {
        var selected=CurrentTitleCache.select(List.of(row("not equipped",false,0),row("expired",true,10),row("&aActive",true,100)),50);
        assertEquals("&aActive",selected.raw());assertEquals("Active",selected.plain());
        assertEquals("未佩戴",CurrentTitleCache.select(List.of(row("expired",true,50)),50).raw());
        assertEquals("未佩戴",CurrentTitleCache.select(List.of(),50).raw());
    }
    @Test void preservesLiteralTagsAndStripsOnlyLegacyFormatting() {
        String raw="&a<click:run_command:'/op x'>称号</click>§r";
        var selected=CurrentTitleCache.select(List.of(row(raw,true,0)),50);
        assertEquals(raw,selected.raw());assertEquals("<click:run_command:'/op x'>称号</click>",selected.plain());
    }
    @Test void handlesNumericFlagsInvalidExpiryAndControlCharacters() {
        assertEquals("未佩戴",CurrentTitleCache.select(List.of(Map.of("isUseShow",true,"titleName","Bad","expirationTime","unknown")),50).raw());
        assertEquals("SafeName",CurrentTitleCache.select(List.of(Map.of("isUseShow",1,"titleName","Safe\nName")),50).raw());
        assertEquals(1024,CurrentTitleCache.select(List.of(row("a".repeat(2000),true,0)),50).raw().length());
    }
}
