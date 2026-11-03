package com.lunarboreal.qqauth.bridge;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class BridgeTest {
    private static final Logger LOG = Logger.getLogger("bridge-test");
    @Test void astrBotRequiresStringsAndKeepsTransportIdentity() {
        var value = GroupProtocol.astrBot("{\"group_id\":\"g\",\"sender_id\":\"real\",\"message\":\"/验证 other 123456\"}");
        assertEquals("real", value.sender());
        for (String invalid : new String[]{"null", "[]", "{}", "{\"group_id\":1,\"sender_id\":\"s\",\"message\":\"m\"}",
                "{\"group_id\":\"g\",\"sender_id\":\" \",\"message\":\"m\"}"})
            assertThrows(RuntimeException.class, () -> GroupProtocol.astrBot(invalid));
    }
    @Test void oneBotOnlyAcceptsGroupEnvelopeAndTopLevelSender() {
        assertNull(GroupProtocol.oneBot("{\"post_type\":\"notice\"}"));
        assertNull(GroupProtocol.oneBot("{\"post_type\":\"message\",\"message_type\":\"private\"}"));
        String prefix = "{\"post_type\":\"message\",\"message_type\":\"group\",\"group_id\":100,\"user_id\":200,";
        assertNull(GroupProtocol.oneBot(prefix + "\"self_id\":200,\"message\":\"test\"}"));
        var parsed = GroupProtocol.oneBot(prefix + "\"sender\":{\"user_id\":999},\"message\":[{\"type\":\"at\",\"data\":{\"qq\":999}},{\"type\":\"text\",\"data\":{\"text\":\"/验证 123456\"}}]}");
        assertEquals("200", parsed.sender()); assertEquals("/验证 123456", parsed.text());
        assertThrows(IllegalArgumentException.class, () -> GroupProtocol.oneBot("{\"post_type\":\"message\",\"message_type\":\"group\",\"group_id\":100,\"message\":\"x\"}"));
    }
    @Test void textFormatsAndPlainTextReplies() {
        assertEquals("raw", GroupProtocol.extractText(JsonParser.parseString("{\"raw_message\":\"raw\",\"message\":\"fallback\"}").getAsJsonObject()));
        assertEquals("fallback", GroupProtocol.extractText(JsonParser.parseString("{\"raw_message\":\"\",\"message\":\"fallback\"}").getAsJsonObject()));
        var action = JsonParser.parseString(GroupProtocol.oneBotReply("123", "[CQ:at,qq=all]")).getAsJsonObject();
        assertEquals("text", action.getAsJsonObject("params").getAsJsonArray("message").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("", new GroupResult(false, "should not leak").reply());
    }
    @Test void authenticatedHttpContractAndLifecycle() throws Exception {
        AtomicReference<String> sender = new AtomicReference<>();
        try (var bridge = new AstrBotHttpServer(LOG, new AstrBotSettings("127.0.0.1", 0, "unit-test-token", 512, 2, 2), (g,s,m) -> {
            sender.set(s); if (m.equals("boom")) throw new IllegalStateException("secret");
            return m.equals("chat") ? GroupResult.ignored() : GroupResult.reply("回复");
        }); var client = HttpClient.newHttpClient()) {
            bridge.start(); bridge.start();
            URI uri = URI.create("http://127.0.0.1:" + bridge.port() + "/qqauth/group");
            String body = "{\"group_id\":\"g\",\"sender_id\":\"s\",\"message\":\"/验证 123456\"}";
            assertEquals(401, post(client, uri, "wrong", body).statusCode()); assertNull(sender.get());
            var response = post(client, uri, "unit-test-token", body);
            assertEquals(200, response.statusCode()); assertTrue(response.body().contains("回复")); assertEquals("s", sender.get());
            assertEquals(400, post(client, uri, "unit-test-token", "null").statusCode());
            assertEquals(413, post(client, uri, "unit-test-token", "x".repeat(513)).statusCode());
            assertEquals(500, post(client, uri, "unit-test-token", body.replace("/验证 123456", "boom")).statusCode());
            assertTrue(post(client, uri, "unit-test-token", body.replace("/验证 123456", "chat")).body().contains("\"handled\":false"));
            assertEquals(405, client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, post(client, URI.create(uri + "/extra"), "unit-test-token", body).statusCode());
            var bearer = HttpRequest.newBuilder(uri).header("Authorization", "Bearer unit-test-token").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(200, client.send(bearer, HttpResponse.BodyHandlers.ofString()).statusCode());
            bridge.close(); assertThrows(IllegalStateException.class, bridge::start);
        }
    }
    private static HttpResponse<String> post(HttpClient client, URI uri, String token, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("X-QQAuth-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void settingsRejectUnsafeValuesAndHideSecrets() {
        assertThrows(IllegalArgumentException.class, () -> new AstrBotSettings("127.0.0.1", 1, ""));
        assertThrows(IllegalArgumentException.class, () -> new OneBotSettings(URI.create("ws://localhost/?token=secret"), "secret", Duration.ofSeconds(1)));
        assertFalse(new AstrBotSettings("127.0.0.1", 1, "top-secret").toString().contains("top-secret"));
        assertFalse(new OneBotSettings(URI.create("ws://localhost/"), "top-secret", Duration.ofSeconds(1)).toString().contains("top-secret"));
        try (var client = new OneBotClient(LOG, new OneBotSettings(URI.create("ws://127.0.0.1:1"), "", Duration.ofMillis(50)), (g,s,m)->GroupResult.ignored())) {
            client.start(); client.start(); client.close(); client.close(); assertThrows(IllegalStateException.class, client::start);
        }
    }
}
