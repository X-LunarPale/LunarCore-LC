package com.lunarboreal.integration.multiverse;

import com.lunarboreal.api.web.HttpException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MvApiTest {
    public static class PublicApi {
        public String value(boolean input){return input?"yes":"no";}
        public String value(String input){return input;}
        public void broken(){throw new IllegalArgumentException("failure");}
    }
    public static class ConfiguredWorld {
        public String getName(){return "world";}
        public boolean isLoaded(){return true;}
        public String getAlias(){return "";}
        public boolean getPvp(){return true;}
        public boolean isAllowWeather(){return false;}
        public boolean isAutoLoad(){return true;}
    }
    @Test void worldRowsIncludeTheCompleteEditableSnapshot()throws Exception{
        assertEquals(Map.of("name","world","loaded",true,"players",0,"alias","","pvp",true,"allowWeather",false,"autoLoad",true),
                MultiverseWebPlugin.worldRow(new ConfiguredWorld(),0));
    }
    @Test void reflectionSupportsOnlyCompatiblePublicSignatures()throws Exception{
        assertEquals("yes",MvApi.call(new PublicApi(),"value",true));
        assertEquals("world",MvApi.call(new PublicApi(),"value","world"));
        assertThrows(NoSuchMethodException.class,()->MvApi.call(new PublicApi(),"value",42));
        assertThrows(IllegalArgumentException.class,()->MvApi.call(new PublicApi(),"broken"));
    }
    @Test void validatorsRejectCoercionAndControlCharacters(){
        assertTrue(MultiverseWebPlugin.bool(true));
        assertFalse(MultiverseWebPlugin.bool("false"));
        assertThrows(RuntimeException.class,()->MultiverseWebPlugin.bool(0));
        assertThrows(RuntimeException.class,()->MultiverseWebPlugin.bool("TRUE"));
        assertThrows(RuntimeException.class,()->MultiverseWebPlugin.text(java.util.Map.of("name","world\nstop"),"name"));
    }
    @Test void configValuesAreStrictAndCanClearAliases(){
        assertEquals(Map.of("alias","","pvp",true,"allowWeather",false),
                MultiverseWebPlugin.configChanges(Map.of("alias","","pvp",true,"allowWeather",false)));
        assertEquals("getPvp",MultiverseWebPlugin.configGetter("pvp"));
        assertEquals("isAllowWeather",MultiverseWebPlugin.configGetter("allowWeather"));
        assertEquals(true,MultiverseWebPlugin.legacyConfigValue("pvp","true"));
        assertEquals(false,MultiverseWebPlugin.legacyConfigValue("autoLoad","false"));
        assertThrows(HttpException.class,()->MultiverseWebPlugin.configChanges(Map.of("seed","123")));
        assertThrows(HttpException.class,()->MultiverseWebPlugin.configChanges(Map.of("pvp","true")));
        assertThrows(HttpException.class,()->MultiverseWebPlugin.configChanges(Map.of("alias","bad\nalias")));
        assertThrows(HttpException.class,()->MultiverseWebPlugin.expectedConfig(Map.of("pvp",true)));
        assertThrows(HttpException.class,()->MultiverseWebPlugin.legacyConfigValue("pvp",1));
    }
}
