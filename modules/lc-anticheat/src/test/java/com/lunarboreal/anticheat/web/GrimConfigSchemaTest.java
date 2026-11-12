package com.lunarboreal.anticheat.web;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GrimConfigSchemaTest {
    private YamlConfiguration yaml(String text) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    private GrimConfigSchema.Field field(String id, String text, String key) throws Exception {
        return GrimConfigSchema.fields(id, yaml(text)).stream().filter(field -> field.key().equals(key))
                .findFirst().orElseThrow();
    }

    @Test void knownWebhookMessagesRemainVisibleWhileWebhookCredentialsAreSecret() throws Exception {
        var message = field("messages", "webhook-test-message: test\n", "webhook-test-message");
        assertFalse(message.secret());
        assertFalse(message.readOnly());
        assertEquals("textarea", message.type());
        var webhook = field("discord", "webhook: private-token\n", "webhook");
        assertTrue(webhook.secret());
        assertEquals("text", webhook.type());
        assertEquals("", webhook.validate(""));
        for (String key : List.of("mysql.password", "mysql.extra-jdbc-params", "mongo.connection-string")) {
            String prefix = key.substring(0, key.indexOf('.'));
            String leaf = key.substring(key.indexOf('.') + 1);
            assertTrue(field("database-" + prefix, prefix + ":\n  " + leaf + ": private\n", key).secret());
        }
    }

    @Test void decimalMovementSettingsDoNotBecomeIntegerFieldsAfterSavingWholeNumbers() throws Exception {
        for (String name : List.of("threshold", "max-advantage", "max-ceiling", "setback-decay-multiplier")) {
            var field = field("config", "Simulation:\n  " + name + ": 1\n", "Simulation." + name);
            assertEquals(0.0001, field.step());
            assertEquals(0.25, field.validate(0.25));
        }
    }

    @Test void versionsAndUnknownFieldsAreReadonlyAndUnknownValuesAreHidden() throws Exception {
        var unknown = field("config", "custom:\n  private: do-not-expose\n", "custom.private");
        assertTrue(unknown.readOnly());
        assertEquals(true, unknown.metadata().get("hidden"));
        for (String flavor : List.of("V3", "unexpected")) {
            var field = field("config", "config-flavor: " + flavor + "\nalerts:\n  print-to-console: true\n", "alerts.print-to-console");
            assertTrue(field.readOnly());
        }
        assertTrue(field("config", "config-version: 99\nmax-transaction-time: 60\n", "max-transaction-time").readOnly());
        assertTrue(field("config", "config-version: 11\n", "config-version").readOnly());
    }

    @Test void knownFieldsUseSchemaTypesAndIncompatibleExistingValuesAreReadonly() throws Exception {
        var badTime = field("config", "max-transaction-time: sixty\n", "max-transaction-time");
        assertEquals("number", badTime.type());
        assertTrue(badTime.readOnly());
        var badSwitch = field("config", "alerts:\n  print-to-console: 'true'\n", "alerts.print-to-console");
        assertEquals("bool", badSwitch.type());
        assertTrue(badSwitch.readOnly());
        var badList = field("messages", "help: single-line\n", "help");
        assertEquals("string-list", badList.type());
        assertTrue(badList.readOnly());
    }

    @Test void exactLimitsAndRegexValidationRejectUnsafeValues() throws Exception {
        var time = field("config", "max-transaction-time: 60\n", "max-transaction-time");
        assertEquals(180L, time.validate(180.0));
        for (Object value : List.of(0, 181, 2.5, "60", Double.NaN))
            assertThrows(HttpException.class, () -> time.validate(value));
        var refresh = field("config", "update-permission-ticks: -1\n", "update-permission-ticks");
        assertEquals(-1L, refresh.validate(-1));
        assertThrows(HttpException.class, () -> refresh.validate(0));
        var ignored = field("config", "client-brand:\n  ignored-clients: []\n", "client-brand.ignored-clients");
        assertEquals(List.of("^vanilla$", ""), ignored.validate(List.of("^vanilla$", "")));
        assertThrows(HttpException.class, () -> ignored.validate(List.of("[")));
    }

    @Test void writeQueueMustBePowerOfTwoAndRelationsAreValidated() throws Exception {
        var queue = field("database", "database:\n  write-path:\n    queue-capacity: 16384\n", "database.write-path.queue-capacity");
        assertEquals(8192L, queue.validate(8192));
        assertThrows(HttpException.class, () -> queue.validate(8191));
        assertThrows(HttpException.class, () -> GrimConfigSchema.validateRelations(yaml(
                "database:\n  write-path:\n    batch-size: 256\n    queue-capacity: 128\n")));
        assertThrows(HttpException.class, () -> GrimConfigSchema.validateRelations(yaml(
                "mysql:\n  pool-settings:\n    minimum-idle: 11\n    maximum-pool-size: 10\n")));
    }

    @Test void punishmentCommandsUseExactProtocolAndRemovalSecondsCannotOverflow() throws Exception {
        String source = "Punishments:\n  Custom:\n    commands: ['1:1 [alert]']\n    remove-violations-after: 300\n";
        var commands = field("punishments", source, "Punishments.Custom.commands");
        assertEquals(List.of("20:40 [alert]", "100:0 kick %player% reason"),
                commands.validate(List.of("20:40 [alert]", "100:0 kick %player% reason")));
        for (String command : List.of("1:1\t[alert]", "1:1 ", "2147483648:1 [alert]", "-1:1 [alert]", "1:1 say hi\nstop"))
            assertThrows(HttpException.class, () -> commands.validate(List.of(command)));
        var seconds = field("punishments", source, "Punishments.Custom.remove-violations-after");
        assertEquals(2147483L, seconds.validate(2147483));
        assertThrows(HttpException.class, () -> seconds.validate(2147484));
    }
}
