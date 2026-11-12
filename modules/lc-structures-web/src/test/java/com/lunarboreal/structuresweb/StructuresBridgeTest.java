package com.lunarboreal.structuresweb;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StructuresBridgeTest {
    public static class Plugin {
        public Object getManagementApi() { return new Api(); }
    }
    public static class Api {
        public int apiVersion() { return 1; }
        public Map<String, Object> status() { return Map.of("restartRequired", true); }
        public Map<String, Object> readConfig() { return Map.of("revision", 7); }
        public Map<String, Object> saveConfig(Map<String, Object> data) throws IOException {
            if (data.containsKey("invalid")) throw new IllegalArgumentException("stale revision");
            if (data.containsKey("io")) throw new IOException("disk full");
            return Map.of("saved", data);
        }
    }
    public static class OldPlugin {
        public Object getManagementApi() { return new OldApi(); }
    }
    public static class OldApi extends Api { @Override public int apiVersion() { return 2; } }
    public static class MissingPlugin { public Object getManagementApi() { return new Object(); } }

    @Test void communicatesAcrossAnIsolatedPluginClassLoaderUsingJdkValuesOnly() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Plugin.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader())) {
            Object plugin = loader.loadClass(Plugin.class.getName()).getConstructor().newInstance();
            assertNotSame(Plugin.class, plugin.getClass());
            var bridge = new StructuresBridge();
            bridge.bind(plugin);
            assertTrue(bridge.connected());
            assertEquals(7, bridge.readConfig().get("revision"));
            assertEquals(true, bridge.status().get("restartRequired"));
            assertEquals(Map.of("saved", Map.of("revision", 7)), bridge.saveConfig(Map.of("revision", 7)));
        }
    }

    @Test void invalidVersionOrMissingMethodsClearThePreviousBinding() {
        for (Object plugin : new Object[]{new OldPlugin(), new MissingPlugin(), new Object()}) {
            var bridge = new StructuresBridge();
            bridge.bind(new Plugin());
            Object token = bridge.connection();
            bridge.bind(plugin);
            assertFalse(bridge.connected());
            assertFalse(bridge.isCurrent(token));
            assertTrue(bridge.problem().contains("不兼容"));
            assertThrows(IllegalStateException.class, bridge::readConfig);
        }
    }

    @Test void unloadAndRebindInvalidateScheduledRestartAndRejectWrites() {
        var bridge = new StructuresBridge();
        bridge.bind(new Plugin());
        Object token = bridge.connection();
        bridge.disconnect();
        assertFalse(bridge.isCurrent(token));
        assertThrows(IllegalStateException.class, () -> bridge.saveConfig(Map.of()));
        bridge.bind(new Plugin());
        assertTrue(bridge.connected());
        assertFalse(bridge.isCurrent(token));
        assertTrue(bridge.isCurrent(bridge.connection()));
    }

    @Test void validationAndIoFailuresPropagateWithoutPretendingTheSaveSucceeded() {
        var bridge = new StructuresBridge();
        bridge.bind(new Plugin());
        assertEquals("stale revision", assertThrows(IllegalArgumentException.class,
                () -> bridge.saveConfig(Map.of("invalid", true))).getMessage());
        assertThrows(IOException.class, () -> bridge.saveConfig(Map.of("io", true)));
        assertTrue(bridge.connected());
    }
}
