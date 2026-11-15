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
    public static class NativeWorld extends ConfiguredWorld {
        public org.bukkit.NamespacedKey getKey(){return new org.bukkit.NamespacedKey("lc_terrain","alpine");}
    }
    public static class WorldManager {
        final ConfiguredWorld loaded=new ConfiguredWorld();
        final NativeWorld unloaded=new NativeWorld();
        public java.util.Collection<?> getWorlds(){return java.util.List.of(loaded);}
        public java.util.Collection<?> getLoadedWorlds(){return java.util.List.of(loaded);}
        public java.util.Collection<?> getUnloadedWorlds(){return java.util.List.of(unloaded);}
    }
    public record KeyedWorld(String key,String name,String alias) {
        public org.bukkit.NamespacedKey getKey(){return org.bukkit.NamespacedKey.fromString(key);}
        public String getName(){return name;}
        public String getAlias(){return alias;}
    }
    public record Option(Object value){public Object getOrNull(){return value;}}
    public static class LookupManager {
        final java.util.List<KeyedWorld> entries;
        LookupManager(KeyedWorld... entries){this.entries=java.util.List.of(entries);}
        public Option getWorld(String name){return new Option(null);}
        public java.util.Collection<?> getWorlds(){return java.util.List.of();}
        public java.util.Collection<?> getUnloadedWorlds(){return entries;}
    }
    @Test void keepsUnloadedWorldsWhenMultiverseOmitsThemFromItsCombinedList()throws Exception{
        WorldManager manager=new WorldManager();
        assertEquals(java.util.List.of(manager.loaded,manager.unloaded),
                new java.util.ArrayList<>(MvApi.worlds(manager)));
    }
    @Test void worldIdentityUsesTheDimensionKeyAndKeepsOldApiCompatibility()throws Exception{
        assertEquals("lc_terrain:alpine",MvApi.identity(new NativeWorld()));
        assertEquals("world",MvApi.identity(new ConfiguredWorld()));
    }
    @Test void resolvesUnloadedWorldsByDimensionKeyNameAndConfiguredAlias()throws Exception{
        KeyedWorld nativeWorld=new KeyedWorld("lc_terrain:jixian","lc_terrain_jixian","极限地形");
        LookupManager manager=new LookupManager(nativeWorld);
        for(String reference:java.util.List.of("lc_terrain:jixian","lc_terrain_jixian","极限地形"))
            assertSame(nativeWorld,MvApi.world(manager,reference));
    }
    @Test void rejectsAmbiguousDisplayNamesButStillAcceptsAnExactDimensionKey()throws Exception{
        KeyedWorld nativeWorld=new KeyedWorld("lc_terrain:jixian","lc_terrain_jixian","极限地形");
        KeyedWorld staleWorld=new KeyedWorld("minecraft:lc_terrain_jixian","lc_terrain_jixian","极限地形");
        LookupManager manager=new LookupManager(nativeWorld,staleWorld);
        assertThrows(IllegalArgumentException.class,()->MvApi.world(manager,"lc_terrain_jixian"));
        assertThrows(IllegalArgumentException.class,()->MvApi.world(manager,"极限地形"));
        assertSame(nativeWorld,MvApi.world(manager,"lc_terrain:jixian"));
    }
    @Test void prefersTheStableRowKeyWhileKeepingNameAndAliasRequestsCompatible(){
        assertEquals("lc_terrain:jixian",MultiverseWebPlugin.worldReference(Map.of("key","lc_terrain:jixian")));
        assertEquals("lc_terrain:jixian",MultiverseWebPlugin.worldReference(Map.of("name","lc_terrain_jixian",
                "row",Map.of("name","lc_terrain_jixian","key","lc_terrain:jixian"))));
        assertEquals("极限地形",MultiverseWebPlugin.worldReference(Map.of("name","极限地形")));
    }
    @Test void worldRowsIncludeTheCompleteEditableSnapshot()throws Exception{
        assertEquals(Map.of("name","world","key","world","loaded",true,"players",0,"alias","","pvp",true,"allowWeather",false,"autoLoad",true),
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
