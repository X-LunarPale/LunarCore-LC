package com.lunarboreal.chat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AiGatewayTest {
    @Test void fetchesModelsEmbeddingsAndSub2ApiBalanceWithBoundedServerSideRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        for (var entry : java.util.Map.of(
                "/v1/models", "{\"data\":[{\"id\":\"chat-b\"},{\"id\":\"chat-a\"},{\"id\":\"chat-b\"}]}",
                "/v1/embeddings", "{\"data\":[{\"embedding\":[1,0,0,0,0,0,0,0]}]}",
                "/v1/usage", "{\"mode\":\"balance\",\"isValid\":true,\"remaining\":12.5,\"unit\":\"USD\"}").entrySet()) {
            server.createContext(entry.getKey(), exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                byte[] payload = entry.getValue().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
        }
        server.start();
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var bot = new AiChatService.Bot("primary", true, "月灵", "persona", "sub2api", base, "chat-a", "", "fixture-key");
            assertEquals(List.of("chat-a", "chat-b"), AiGateway.models(client, bot));
            assertEquals(8, AiGateway.embedding(client, base, "fixture-key", "embed", "hello").length);
            var balance = AiGateway.balance(client, bot);
            assertEquals(12.5, balance.get("remaining"));
            assertEquals(true, balance.get("available"));
            assertEquals("Bearer fixture-key", authorization.get());
            assertFalse(balance.toString().contains("fixture-key"));
            var unofficial = new AiChatService.Bot("primary", true, "月灵", "persona", "deepseek", base, "chat-a", "", "fixture-key");
            assertThrows(RuntimeException.class, () -> AiGateway.balance(client, unofficial));
        } finally { server.stop(0); }
    }
}
