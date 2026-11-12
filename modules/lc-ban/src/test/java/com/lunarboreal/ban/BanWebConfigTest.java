package com.lunarboreal.ban;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class BanWebConfigTest {
    @TempDir Path directory;

    @Test void browserSaveUpdatesDiskAndRuntimeSettingsTogether() throws Exception {
        BanModule module = module();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("server-name", "LunarCore-\u7409\u6708\u00b7\u51b0\u6f9c");
        body.put("appeal-url", "https://example.org/appeal");
        body.put("date-format", "yyyy");
        body.put("default-reason", "Server rules");
        body.put("broadcast.enabled", false);
        body.put("broadcast.api", "false");
        body.put("exempt-enabled", true);
        body.put("warn.expire", "30d");
        body.put("warn.actions", "3=tempmute 1h Three warnings\n5=tempban 1d Five warnings\n7=ban Seven warnings");
        body.put("warn.reset-after-action", false);
        body.put("muted-commands", "/msg\nmail,reply");
        body.put("duration-limits.enabled", true);
        body.put("duration-limits.default", "1d");
        body.put("reasons", "spam:\n  reason: Repeated messages\n  duration: 1h\n");

        new BanWeb(module).saveSettings(body);

        YamlConfiguration disk = disk();
        assertEquals(body.get("server-name"), module.serverName());
        assertEquals(body.get("server-name"), disk.getString("server-name"));
        assertEquals("https://example.org/appeal", disk.getString("appeal-url"));
        assertEquals(4, module.formatDate(0L).length());
        assertFalse(module.config().getBoolean("broadcast.enabled"));
        assertFalse(module.config().getBoolean("broadcast.api"));
        assertEquals("tempmute 1h Three warnings", disk.getString("warn.actions.3"));
        assertEquals("tempban 1d Five warnings", disk.getString("warn.actions.5"));
        assertEquals("ban Seven warnings", disk.getString("warn.actions.7"));
        assertFalse(disk.getBoolean("warn.reset-after-action"));
        assertEquals(List.of("msg", "mail", "reply"), disk.getStringList("muted-commands"));
        assertTrue(module.isMutedCommand("lc:reply"));
        assertFalse(module.isMutedCommand("tell"));
        assertEquals("Repeated messages", module.template("spam").orElseThrow().reason());
        assertEquals("1d", disk.getString("duration-limits.default"));
        assertEquals(module.config().saveToString(), disk.saveToString());
    }

    @Test void replacingOrClearingTablesDoesNotRestoreDefaultEntries() throws Exception {
        BanModule module = module();
        BanWeb web = new BanWeb(module);

        web.saveSettings(Map.of("warn.actions", "9=kick Nine warnings",
                "reasons", "custom:\n  reason: Custom rule\n"));
        assertEquals(List.of("9"), List.copyOf(module.config().getConfigurationSection("warn.actions").getKeys(false)));
        assertNull(module.config().getString("warn.actions.3"));
        assertEquals(List.of("custom"), module.templateIds());
        assertTrue(module.template("hack").isEmpty());
        assertNull(disk().getString("warn.actions.3"));

        web.saveSettings(Map.of("warn.actions", "", "reasons", ""));
        assertTrue(module.config().isConfigurationSection("warn.actions"));
        assertTrue(module.config().getConfigurationSection("warn.actions").getKeys(false).isEmpty());
        assertTrue(module.templateIds().isEmpty());
        assertTrue(disk().isConfigurationSection("warn.actions"));
        assertTrue(disk().getConfigurationSection("warn.actions").getKeys(false).isEmpty());
        assertTrue(disk().isConfigurationSection("reasons"));
        assertTrue(disk().getConfigurationSection("reasons").getKeys(false).isEmpty());
    }

    @Test void invalidReasonYamlDoesNotChangeOtherSettings() throws Exception {
        BanModule module = module();
        String before = Files.readString(directory.resolve("config.yml"));
        String server = module.serverName();

        assertThrows(HttpException.class, () -> new BanWeb(module).saveSettings(
                Map.of("server-name", "Changed", "reasons", "invalid: [")));

        assertEquals(server, module.serverName());
        assertEquals(server, module.config().getString("server-name"));
        assertEquals(before, Files.readString(directory.resolve("config.yml")));
    }

    @Test void failedDiskWriteLeavesRuntimeSettingsIntact() throws Exception {
        BanModule module = module();
        String before = module.config().saveToString();
        String server = module.serverName();
        boolean replyWasMuted = module.isMutedCommand("reply");
        Path target = directory.resolve("config.yml");
        Files.delete(target);
        Files.createDirectory(target);

        assertThrows(IOException.class, () -> new BanWeb(module).saveSettings(
                Map.of("server-name", "Not saved", "muted-commands", "custom")));

        assertEquals(before, module.config().saveToString());
        assertEquals(server, module.serverName());
        assertEquals(replyWasMuted, module.isMutedCommand("reply"));
        assertFalse(module.isMutedCommand("custom"));
    }

    private BanModule module() {
        BanModule module = new BanModule();
        module.init(null, directory.toFile(), getClass().getClassLoader(), Logger.getAnonymousLogger());
        module.config();
        module.reloadSettings();
        return module;
    }

    private YamlConfiguration disk() {
        return YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
    }
}
