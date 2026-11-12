package com.lunarboreal.ban;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.api.module.ModuleDescription;
import com.lunarboreal.api.service.BanService.Punishment;
import com.lunarboreal.api.service.BanService.Type;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("Temporarily replaces the LC global entry point")
class BanScreenTest {
    @TempDir Path directory;
    private LunarBorealAPI previousApi;
    private BanModule module;
    private TestMessages messages;

    @BeforeEach
    void setup() {
        previousApi = LC.isLoaded() ? LC.get() : null;
        module = new BanModule();
        module.init(new ModuleDescription("ban", "Ban", "test", "", "",
                        List.of(), List.of(), List.of()), directory.toFile(),
                getClass().getClassLoader(), Logger.getAnonymousLogger());
        messages = new TestMessages(directory.resolve("language.yml"));
        LunarBorealAPI api = (LunarBorealAPI) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{LunarBorealAPI.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("messages")) return messages;
                    throw new AssertionError("Unexpected API access: " + method.getName());
                });
        LC.setInstance(api);
        messages.registerBundle("ban", getClass().getClassLoader());
    }

    @AfterEach
    void cleanup() {
        LC.setInstance(previousApi);
    }

    static Stream<Arguments> screens() {
        return Stream.of("zh_CN", "en_US").flatMap(locale -> Stream.of(
                Arguments.of(locale, Type.BAN, null),
                Arguments.of(locale, Type.BAN, 1_800_000L),
                Arguments.of(locale, Type.IPBAN, null),
                Arguments.of(locale, Type.IPBAN, 1_800_000L),
                Arguments.of(locale, Type.KICK, null)));
    }

    @ParameterizedTest
    @MethodSource("screens")
    void allDefaultScreensUseConfiguredServerName(String locale, Type type, Long duration) {
        messages.locale = locale;
        messages.registerBundle("ban", getClass().getClassLoader());
        module.loadRuntimeConfig();
        module.config().set("server-name", "Configured Server");
        module.reloadSettings();

        String screen = plain(module.screen(punishment(type, duration)));

        assertEquals("Configured Server", screen.lines().findFirst().orElseThrow());
        assertFalse(screen.contains("Lunar Boreal"));
        assertFalse(screen.contains("{server}"));
    }

    @Test
    void oldDefaultHeaderUsesCurrentNameAndCustomHeadersStayIntact() {
        module.loadRuntimeConfig();
        module.config().set("server-name", "Current Server");
        module.reloadSettings();
        messages.language.set("screen.kick", List.of(
                "<gradient:#7ec8ff:#b98cff><bold>Lunar Boreal</bold></gradient>",
                "<gray>Reason: {reason}"));
        assertEquals("Current Server", plain(module.screen(punishment(Type.KICK, null))).lines().findFirst().orElseThrow());

        messages.language.set("screen.kick", List.of("<gold>Custom Lunar Boreal Hub", "{reason}"));
        assertEquals("Custom Lunar Boreal Hub",
                plain(module.screen(punishment(Type.KICK, null))).lines().findFirst().orElseThrow());
    }

    @Test
    void settingsRefreshUpdatesScreenFieldsAndServerNameWithoutBukkitAccess() throws Exception {
        module.loadRuntimeConfig();
        module.config().set("server-name", "<red>Updated Server");
        module.config().set("appeal-url", "https://example.net/new-appeal");
        module.config().set("date-format", "'updated-'yyyy");
        module.config().set("muted-commands", List.of("/new-command"));
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(module::reloadSettings).get();
        }

        String screen = plain(module.screen(punishment(Type.BAN, 1_800_000L)));

        assertTrue(screen.startsWith("<red>Updated Server\n"));
        assertTrue(screen.contains("https://example.net/new-appeal"));
        assertTrue(screen.contains("updated-2026"));
        assertEquals("<red>Updated Server", module.serverName());
        assertTrue(module.isMutedCommand("plugin:new-command"));
        assertFalse(module.isMutedCommand("msg"));
    }

    @Test
    void reloadReadsBothConfigAndEditedLanguageFile() throws Exception {
        module.loadRuntimeConfig();
        module.config().set("server-name", "Reloaded Server");
        module.config().save(directory.resolve("config.yml").toFile());
        YamlConfiguration language = new YamlConfiguration();
        language.set("screen.kick", List.of("<gold>Edited {server}", "{reason}"));
        language.save(directory.resolve("language.yml").toFile());
        int registrations = messages.registrations;

        module.onReload();

        assertEquals(registrations + 1, messages.registrations);
        assertTrue(plain(module.screen(punishment(Type.KICK, null))).startsWith("Edited Reloaded Server\n"));
    }

    @Test
    void initialLoadAndReloadPreserveEmptyTablesAndStillFillMissingScalars() throws Exception {
        YamlConfiguration saved = new YamlConfiguration();
        saved.set("server-name", "Existing Server");
        saved.createSection("warn.actions");
        saved.createSection("reasons");
        saved.save(directory.resolve("config.yml").toFile());

        module.loadRuntimeConfig();
        assertReplacementTablesAreEmpty(module.config());
        assertEquals("Existing Server", module.config().getString("server-name"));
        assertTrue(module.config().getBoolean("broadcast.enabled"));
        assertEquals("yyyy-MM-dd HH:mm", module.config().getString("date-format"));

        module.onReload();

        assertReplacementTablesAreEmpty(module.config());
        assertReplacementTablesAreEmpty(
                YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile()));
    }

    @Test
    void replacementTablesDoNotRegainUnwantedDefaultEntries() throws Exception {
        YamlConfiguration saved = new YamlConfiguration();
        saved.set("warn.actions.2", "kick Custom reason");
        saved.set("reasons.custom.reason", "Custom reason");
        saved.save(directory.resolve("config.yml").toFile());

        module.loadRuntimeConfig();
        module.onReload();

        assertEquals(List.of("custom"), module.templateIds());
        assertEquals(java.util.Set.of("2"), module.config().getConfigurationSection("warn.actions").getKeys(false));
        assertEquals("Custom reason", module.template("custom").orElseThrow().reason());
        assertTrue(module.template("hack").isEmpty());
    }

    @Test
    void invalidConfigDoesNotGetOverwrittenByFirstAccessDefaults() throws Exception {
        Path config = directory.resolve("config.yml");
        String invalid = "warn: [broken";
        Files.writeString(config, invalid);

        assertThrows(IllegalStateException.class, module::loadRuntimeConfig);

        assertEquals(invalid, Files.readString(config));
    }

    @ParameterizedTest
    @CsvSource({
            "TimerLimit,客户端计时异常",
            "Timer,客户端计时异常",
            "GroundSpoof,落地状态异常",
            "NoFall,下落状态异常",
            "Simulation,移动校验异常",
            "Reach,攻击距离异常",
            "Post,操作顺序异常"
    })
    void anticheatReasonUsesTheActualCheck(String check, String expected) {
        assertEquals(expected, module.anticheatReason(check, "检测到移动异常"));
    }

    @Test
    void unknownChecksKeepTheConfiguredFallback() {
        assertEquals("自定义检测项", module.anticheatReason("CustomCheck", "自定义检测项"));
        assertEquals("检测到异常行为", module.anticheatReason(null, ""));
    }

    @Test
    void automatedCommandMetadataIsSeparateFromTheReasonAndDuration() {
        String[] command = {"Player", "1h", "--source=anticheat", "--check=TimerLimit", "检测到移动异常", "-s"};

        BanCommands.Parsed parsed = BanCommands.parse(command, true);

        assertEquals(List.of("Player", "1h", "检测到移动异常"), parsed.args());
        assertTrue(parsed.anticheat());
        assertTrue(parsed.silent());
        assertEquals("TimerLimit", parsed.check());

        BanCommands.Parsed playerCommand = BanCommands.parse(command, false);
        assertFalse(playerCommand.anticheat());
        assertNull(playerCommand.check());
        assertTrue(playerCommand.args().contains("--source=anticheat"));
    }

    @Test
    void operatorsAreReadableWithoutRenamingManualConsoleActions() {
        assertEquals("反作弊", module.displayOperator("Anticheat"));
        assertEquals("控制台", module.displayOperator("Console"));
        assertEquals("控制台", module.displayOperator(null));
        assertEquals("反作弊 · 自动处罚", module.displayOperator("AutoWarn(Anticheat)"));
        assertEquals("Admin", module.displayOperator("Admin"));
    }

    @Test
    void warningsUseTheirOwnHeadingAndBrightText() {
        String personal = module.raw("notify.warned");
        String broadcast = module.raw("broadcast.warn");

        for (String message : List.of(personal, broadcast)) {
            assertTrue(message.contains("警告"));
            assertTrue(message.contains("\n"));
            assertFalse(message.contains("<prefix>"));
            assertFalse(message.contains("封禁"));
            assertFalse(message.contains("<gray>"));
            assertFalse(message.contains("<dark_gray>"));
            assertTrue(message.contains("{operator}"));
        }
    }

    private static void assertReplacementTablesAreEmpty(YamlConfiguration config) {
        assertTrue(config.getConfigurationSection("warn.actions").getKeys(false).isEmpty());
        assertTrue(config.getConfigurationSection("reasons").getKeys(false).isEmpty());
    }

    private static Punishment punishment(Type type, Long duration) {
        long created = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();
        return new Punishment(42, type, UUID.randomUUID(), "Player", "127.0.0.1",
                "Test reason", "Operator", created, duration == null ? null : created + duration,
                type != Type.KICK, null);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static final class TestMessages implements MessageService {
        private final MiniMessage miniMessage = MiniMessage.miniMessage();
        private final Path languageFile;
        private YamlConfiguration language;
        private String locale = "zh_CN";
        private int registrations;

        private TestMessages(Path languageFile) {
            this.languageFile = languageFile;
        }

        @Override
        public void registerBundle(String namespace, ClassLoader loader) {
            registrations++;
            if (Files.isRegularFile(languageFile)) {
                language = YamlConfiguration.loadConfiguration(languageFile.toFile());
            } else {
                try (var stream = loader.getResourceAsStream("lang/" + locale + ".yml")) {
                    assertNotNull(stream);
                    language = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(stream, StandardCharsets.UTF_8));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }
        }

        @Override public void unregisterBundle(String namespace) {}
        @Override public void send(CommandSender to, String key, Object... args) {}
        @Override public String locale() { return locale; }

        @Override
        public String raw(String key, Object... args) {
            String path = key.substring("ban.".length());
            String text = language.isList(path)
                    ? String.join("\n", language.getStringList(path))
                    : language.getString(path, key);
            return replace(text, args);
        }

        @Override
        public Component component(String key, Object... args) {
            return parse(raw(key), args);
        }

        @Override
        public Component parse(String template, Object... args) {
            return miniMessage.deserialize(replace(template, args));
        }

        private String replace(String text, Object... args) {
            for (int i = 0; i + 1 < args.length; i += 2) {
                text = text.replace("{" + args[i] + "}", miniMessage.escapeTags(String.valueOf(args[i + 1])));
            }
            return text;
        }
    }
}
