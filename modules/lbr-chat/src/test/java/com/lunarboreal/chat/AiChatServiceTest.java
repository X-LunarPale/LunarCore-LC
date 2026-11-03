package com.lunarboreal.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunarboreal.api.module.ModuleDescription;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AiChatServiceTest {
    private static final AiChatService.Settings DISABLED = new AiChatService.Settings(false, "月灵", "友善地回复",
            "https://api.openai.com/v1", "", "mention", 100, 20, "");

    @Test void settingsNeverExposeKeyAndCannotEnableWithoutOne() {
        assertFalse(DISABLED.publicView().containsKey("apiKey"));
        assertEquals(100, DISABLED.publicView().get("proactiveProbability"));
        assertEquals(false, DISABLED.publicView().get("keyConfigured"));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("enabled", true, "model", "demo")));
        var enabled = AiChatService.update(DISABLED, Map.of("enabled", true, "model", "demo", "apiKey", "local-secret"));
        assertEquals(true, enabled.publicView().get("keyConfigured"));
        assertFalse(enabled.publicView().toString().contains("local-secret"));
        assertEquals("local-secret", AiChatService.update(enabled, Map.of("apiKey", "")).apiKey());
        assertThrows(RuntimeException.class, () -> AiChatService.update(enabled, Map.of("clearApiKey", true)));
        assertEquals("", AiChatService.update(enabled, Map.of("clearApiKey", true, "enabled", false)).apiKey());
        assertThrows(RuntimeException.class, () -> AiChatService.update(enabled, Map.of("baseUrl", "https://other.example/v1")));
        assertEquals("local-secret", AiChatService.update(enabled,
                Map.of("baseUrl", "https://other.example/v1", "apiKey", "local-secret")).apiKey());
        assertEquals("", AiChatService.update(enabled,
                Map.of("enabled", false, "baseUrl", "https://other.example/v1", "clearApiKey", true)).apiKey());
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("apiKey", "bad\tkey")));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("apiKey", "非ASCII")));
    }

    @Test void virtualChatUsesPlayerTemplateWithoutBorrowingPersonalPlaceholders() {
        YamlConfiguration format = new YamlConfiguration();
        format.set("groups.default.format", "<displayname> %lbr_title% {world}: <message>");
        ChatFormatter formatter = new ChatFormatter();
        formatter.load(format);
        Component rendered = formatter.formatVirtualChat("overworld", "月灵", Component.text("你好"));
        assertEquals("月灵  overworld: 你好", PlainTextComponentSerializer.plainText().serialize(rendered));
    }

    @Test void rejectsInvalidConfigAndAcceptsSelectedVersionRoot() {
        assertEquals("https://example.net/api/v1/chat/completions",
                AiChatService.endpoint("https://example.net/api/v1/").toString());
        for (String invalid : List.of("file:///etc/passwd", "https://user:secret@example.net/v1",
                "https://example.net/v1?key=foo", "https://example.net/v1#fragment", "http://example.net/../admin",
                "https://example.net:65536/v1")) {
            assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("baseUrl", invalid)));
        }
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("name", "<click:run_command>")));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("cooldownSeconds", 1)));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("cooldownSeconds", 5.5)));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("cooldownSeconds", 4_294_967_301L)));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("unexpected", 123)));
        assertTrue(AiChatService.invoked("大家好 @月灵", DISABLED));
        assertFalse(AiChatService.invoked("大家好", DISABLED));
        assertTrue(AiChatService.invoked("大家好", AiChatService.update(DISABLED, Map.of("replyMode", "all"))));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("proactiveProbability", -1)));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("proactiveProbability", 101)));
        assertThrows(RuntimeException.class, () -> AiChatService.update(DISABLED, Map.of("proactiveProbability", 50.5)));
    }

    @Test void mentionsAlwaysReplyWhileUnmentionedChatUsesConfiguredProbability() {
        var mentionOnly = AiChatService.update(DISABLED, Map.of("proactiveProbability", 100));
        assertFalse(AiChatService.selected("大家好", mentionOnly, 0));
        assertTrue(AiChatService.selected("大家好 @月灵", mentionOnly, 99));

        var neverProactive = AiChatService.update(DISABLED, Map.of("replyMode", "all", "proactiveProbability", 0));
        assertFalse(AiChatService.selected("大家好", neverProactive, 0));
        assertTrue(AiChatService.selected("@月灵 你好", neverProactive, 99));

        var sometimes = AiChatService.update(neverProactive, Map.of("proactiveProbability", 25));
        assertTrue(AiChatService.selected("大家好", sometimes, 24));
        assertFalse(AiChatService.selected("大家好", sometimes, 25));
        var always = AiChatService.update(sometimes, Map.of("proactiveProbability", 100));
        assertTrue(AiChatService.selected("大家好", always, 99));
    }

    @Test void onlyOneChatCanReserveBeforeMainThreadDispatch(@TempDir Path directory) throws Exception {
        AiChatService service = service(directory);
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            assertFalse(service.acceptsPublicChat());
            assertNull(service.reserveRequest(service.settings(), "@月灵 hello", System.currentTimeMillis(), 0));
            service.save(Map.of("enabled", true, "model", "demo", "apiKey", "unit-test-key"));
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AiChatService.RequestTicket>> reservations = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                reservations.add(callers.submit(() -> {
                    start.await();
                    return service.reserveRequest(service.settings(), "@月灵 hello", System.currentTimeMillis(), 0);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<AiChatService.RequestTicket> result : reservations) {
                if (result.get(2, TimeUnit.SECONDS) != null) accepted++;
            }
            assertEquals(1, accepted);
            assertFalse(service.acceptsPublicChat());
        } finally {
            service.stop();
        }
    }

    @Test void reloadAndStopInvalidateRepliesWithoutReleasingAnotherRequest(@TempDir Path directory) {
        AiChatService service = service(directory);
        try {
            service.save(Map.of("enabled", true, "model", "demo", "apiKey", "unit-test-key"));
            var old = service.reserveRequest(service.settings(), "@月灵 hello", System.currentTimeMillis(), 0);
            assertNotNull(old);
            assertTrue(service.isCurrentRequest(old));
            service.reload();
            assertFalse(service.isCurrentRequest(old));
            service.finishRequest(old);
            var current = service.reserveRequest(service.settings(), "@月灵 hello", System.currentTimeMillis(), 0);
            assertNotNull(current);
            service.finishRequest(old);
            assertFalse(service.acceptsPublicChat());
            assertNull(service.reserveRequest(service.settings(), "@月灵 hello", System.currentTimeMillis(), 0));
            service.save(Map.of("enabled", false));
            assertFalse(service.isCurrentRequest(current));
            service.finishRequest(current);
            assertFalse(service.acceptsPublicChat());
            service.stop();
            assertFalse(service.isCurrentRequest(current));
            assertNull(service.reserveRequest(service.settings(), "@月灵 hello", System.currentTimeMillis(), 0));
        } finally {
            service.stop();
        }
    }

    @Test void requestExecutorHasOneWorkerAndOneWaitingSlot() throws Exception {
        ExecutorService executor = AiChatService.requestExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> {
                started.countDown();
                release.await();
                return Thread.currentThread().isDaemon();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Future<Integer> second = executor.submit(() -> 42);
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            release.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertEquals(42, second.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static AiChatService service(Path directory) {
        ChatModule module = new ChatModule();
        module.init(new ModuleDescription("chat", "Chat", "test", ChatModule.class.getName(), "",
                        List.of(), List.of(), List.of()), directory.toFile(),
                AiChatServiceTest.class.getClassLoader(), Logger.getAnonymousLogger());
        return new AiChatService(module);
    }

    @Test void mockProviderUsesAuthorizationAndBoundsUntrustedReply() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.createContext("/v1/chat/completions", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
                byte[] body = "{\"choices\":[{\"message\":{\"content\":\"<click:run_command:'/op Steve'>你好</click>\\n欢迎\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            URI endpoint = AiChatService.endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            String response = AiChatService.complete(client, endpoint, "local-secret", "demo", "友善地回复", "Steve",
                    "@月灵 今天怎么样", List.of(new AiChatService.Exchange("昨天好", "你好")));
            assertEquals("Bearer local-secret", authorization.get());
            assertEquals("demo", request.get().get("model").getAsString());
            assertEquals(4, request.get().getAsJsonArray("messages").size());
            assertEquals("system", request.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
            assertEquals("<click:run_command:'/op Steve'>你好</click> 欢迎", response);
            Component rendered = ChatFormatter.render(null, "<displayname><gray> » </gray><message>", Map.of(),
                    Placeholder.component("displayname", Component.text("月灵")),
                    Placeholder.component("message", Component.text(response)));
            assertEquals("月灵 » " + response, PlainTextComponentSerializer.plainText().serialize(rendered));
            assertNull(rendered.clickEvent());
            assertNull(rendered.children().getLast().clickEvent());
        } finally {
            server.stop(0);
        }
    }

    @Test void neverFollowsProviderRedirectWithKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/steal");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.start();
            URI endpoint = AiChatService.endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            Exception failure = assertThrows(Exception.class, () -> AiChatService.complete(client, endpoint,
                    "local-secret", "demo", "友善地回复", "Steve", "hi", List.of()));
            assertFalse(failure.getMessage().contains("local-secret"));
        } finally {
            server.stop(0);
        }
    }

    @Test void rejectsOversizedResponseAndCancelsBodyThatStallsAfterHeaders() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.createContext("/large/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] payload = new byte[AiChatService.MAX_RESPONSE_BYTES + 1];
                exchange.sendResponseHeaders(200, payload.length);
                try { exchange.getResponseBody().write(payload); }
                finally { exchange.close(); }
            });
            server.createContext("/stall/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                try { releaseBody.await(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start();
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThrows(java.io.IOException.class, () -> AiChatService.complete(client,
                    AiChatService.endpoint(root + "/large"), "local-secret", "demo", "persona",
                    "Steve", "hello", List.of()));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThrows(java.io.IOException.class, () -> AiChatService.complete(client,
                            AiChatService.endpoint(root + "/stall"), "local-secret", "demo", "persona",
                            "Steve", "hello", List.of(), Duration.ofMillis(200))));
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    @Test void virtualChatUsesCustomPlayerTemplateAndLiteralModelText() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("groups.default.permission", "");
        config.set("groups.default.priority", 0);
        config.set("groups.default.format", "<gray>[{world}]</gray> <displayname><gray> » </gray><message>");
        ChatFormatter formatter = new ChatFormatter();
        formatter.load(config);
        Component rendered = formatter.formatVirtualChat("survival", "月灵",
                Component.text("<click:run_command:'/op Steve'>你好</click>"));
        assertEquals("[survival] 月灵 » <click:run_command:'/op Steve'>你好</click>",
                PlainTextComponentSerializer.plainText().serialize(rendered));
        assertNull(rendered.children().getLast().clickEvent());
    }

    @Test void multipleCharactersSelectMentionsAndDisabledCharactersStayOut() {
        var settings = AiChatService.update(DISABLED, Map.of("bots", List.of(
                Map.of("id", "helper", "enabled", true, "name", "小梦"),
                Map.of("id", "disabled", "enabled", false, "name", "休息中"))));
        assertEquals("helper", AiChatService.selectBot("@小梦 你好", settings, 99, 0).id());
        assertNull(AiChatService.selectBot("@休息中 你好", settings, 0, 0));
        var proactive = AiChatService.update(settings, Map.of("replyMode", "all", "proactiveProbability", 25));
        assertNull(AiChatService.selectBot("大家好", proactive, 25, 1));
        assertEquals("helper", AiChatService.selectBot("大家好", proactive, 24, 1).id());
    }

    @Test void retriesFallbackOnceForRateLimitsButNotAuthenticationFailures() throws Exception {
        List<String> requests = new ArrayList<>();
        var status = new java.util.concurrent.atomic.AtomicInteger(429);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.createContext("/v1/chat/completions", exchange -> {
                String model = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject().get("model").getAsString();
                requests.add(model);
                if (model.equals("main") || status.get() == 500) exchange.sendResponseHeaders(status.get(), -1);
                else {
                    byte[] payload = "{\"choices\":[{\"message\":{\"content\":\"fallback reply\"}}]}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, payload.length);
                    exchange.getResponseBody().write(payload);
                }
                exchange.close();
            });
            server.start();
            var bot = new AiChatService.Bot("primary", true, "月灵", "persona", "custom",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "main", "fallback", "fixture-key");
            assertEquals("fallback reply", AiChatService.completeWithFallback(client, bot, "persona", "Steve", "hi", List.of()));
            assertEquals(List.of("main", "fallback"), requests);
            requests.clear();
            status.set(401);
            assertThrows(java.io.IOException.class, () -> AiChatService.completeWithFallback(client, bot, "persona", "Steve", "hi", List.of()));
            assertEquals(List.of("main"), requests);
            requests.clear();
            status.set(500);
            assertThrows(java.io.IOException.class, () -> AiChatService.completeWithFallback(client, bot, "persona", "Steve", "hi", List.of()));
            assertEquals(List.of("main", "fallback"), requests);
        } finally { server.stop(0); }
    }
}
