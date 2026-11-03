package com.lunarboreal.qqauth.bridge;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class OneBotLifecycleTest {
    @Test void fragmentedEventRepliesThenReconnectsAndCloses() throws Exception {
        try (ServerSocket peer = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            peer.setSoTimeout(8000);
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<String> reply = new CompletableFuture<>();
            CompletableFuture<Void> reconnected = new CompletableFuture<>();
            CompletableFuture<Void> release = new CompletableFuture<>();
            Thread server = new Thread(() -> {
                try {
                    try (Socket first = peer.accept()) {
                        first.setSoTimeout(5000); handshake(first);
                        String event = "{\"post_type\":\"message\",\"message_type\":\"group\",\"group_id\":123,\"user_id\":456,\"message\":\"test\"}";
                        frame(first, 1, event.substring(0, 20));
                        frame(first, 128, event.substring(20));
                        reply.complete(readFrame(first));
                    }
                    try (Socket second = peer.accept()) {
                        handshake(second); reconnected.complete(null);
                        release.get(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) { reply.completeExceptionally(e); reconnected.completeExceptionally(e); }
            }, "onebot-test-peer");
            server.setDaemon(true); server.start();
            try (var client = new OneBotClient(Logger.getLogger("onebot-test"),
                    new OneBotSettings(URI.create("ws://127.0.0.1:" + peer.getLocalPort()), "local-test", Duration.ofMillis(20)),
                    (g,s,m) -> { assertEquals("123",g); assertEquals("456",s); assertEquals("test",m); calls.incrementAndGet(); return GroupResult.reply("local reply"); })) {
                client.start();
                assertTrue(reply.get(8, TimeUnit.SECONDS).contains("local reply"));
                reconnected.get(8, TimeUnit.SECONDS);
                assertEquals(1, calls.get()); client.close();
            } finally { release.complete(null); server.join(2000); }
        }
    }
    private static void handshake(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line, key = null; boolean auth = false;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.regionMatches(true,0,"Sec-WebSocket-Key: ",0,19)) key = line.substring(19).trim();
            if (line.equalsIgnoreCase("Authorization: Bearer local-test")) auth = true;
        }
        if (key == null || !auth) throw new IOException("Missing handshake headers");
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    private static void frame(Socket socket, int first, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream(); out.write(first); out.write(bytes.length); out.write(bytes); out.flush();
    }
    private static String readFrame(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        in.readUnsignedByte(); int flags = in.readUnsignedByte(); int size = flags & 127;
        if (size == 126) size = in.readUnsignedShort();
        if (size > 65536 || size == 127) throw new IOException("Oversized test frame");
        byte[] mask = (flags & 128) != 0 ? in.readNBytes(4) : null;
        byte[] data = in.readNBytes(size);
        if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
        return new String(data, StandardCharsets.UTF_8);
    }
}
