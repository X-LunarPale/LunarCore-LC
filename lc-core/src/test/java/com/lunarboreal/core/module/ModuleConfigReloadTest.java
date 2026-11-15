package com.lunarboreal.core.module;

import com.lunarboreal.api.module.LcModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModuleConfigReloadTest {
    @Test
    void skipsRestartRequiredModulesAndContinuesAfterRealFailures() {
        List<String> invoked = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, LcModule> modules = new LinkedHashMap<>();
        modules.put("chat", module(true, () -> invoked.add("chat")));
        modules.put("qqauth", module(false, () -> fail("Restart-only module must not receive onReload")));
        modules.put("broken", module(true, () -> { throw new IllegalStateException("invalid config"); }));
        modules.put("world", module(true, () -> invoked.add("world")));

        var result = LcModuleManager.reloadConfigs(modules, (id, error) -> failures.add(id));

        assertEquals(2, result.reloaded());
        assertEquals(List.of("chat", "world"), invoked);
        assertEquals(List.of("qqauth"), result.restartRequired());
        assertEquals(List.of("broken"), result.failed());
        assertEquals(List.of("broken"), failures);
    }

    @Test
    void reportIsImmutableAndHandlesNoLoadedModules() {
        var result = LcModuleManager.reloadConfigs(Map.of(), (id, error) -> fail("No error expected"));

        assertEquals(0, result.reloaded());
        assertTrue(result.restartRequired().isEmpty());
        assertTrue(result.failed().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.failed().add("changed"));
        assertThrows(UnsupportedOperationException.class, () -> result.restartRequired().add("changed"));
    }

    private static LcModule module(boolean configurable, Runnable reload) {
        return new LcModule() {
            @Override public void onEnable() {}
            @Override public boolean supportsConfigReload() { return configurable; }
            @Override public void onReload() { reload.run(); }
        };
    }
}
