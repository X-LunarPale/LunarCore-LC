/* Copyright (c) 2026 QQAuth contributors. SPDX-License-Identifier: MIT
 * Adapted from QQAuth OneBotClient (generation-guard reconnect); see bridge/LICENSE. */
package com.lunarboreal.qqauth.bridge;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** OneBot v11 forward WebSocket. Only the configured peer attests sender IDs.
 * close() is terminal; construct a new adapter for changed settings. */
public final class OneBotClient implements AutoCloseable {
    private final OneBotSettings settings;
    private final GroupProcessor processor;
    private final Logger log;
    private final ScheduledExecutorService scheduler;
    private final ThreadPoolExecutor workers;
    private final HttpClient http;
    private boolean running, closed, reconnectScheduled;
    private long generation;
    private WebSocket socket;
    private CompletableFuture<WebSocket> connecting;
    public OneBotClient(Logger log, OneBotSettings settings, GroupProcessor processor) {
        this.log = Objects.requireNonNull(log); this.settings = Objects.requireNonNull(settings); this.processor = Objects.requireNonNull(processor);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "QQAuth-WS-reconnect"));
        workers = new ThreadPoolExecutor(settings.workers(), settings.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), r -> daemon(r, "QQAuth-WS-message"), new ThreadPoolExecutor.AbortPolicy());
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Bridge closed");
        if (!running) { running = true; connect(); }
    }
    private synchronized void connect() {
        if (!running) return;
        reconnectScheduled = false;
        long gen = ++generation;
        WebSocket old = socket; socket = null;
        if (old != null) old.abort();
        try {
            WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
            if (!settings.token().isEmpty()) builder.header("Authorization", "Bearer " + settings.token());
            connecting = builder.buildAsync(settings.endpoint(), new Connection(gen));
            connecting.whenComplete((ws, error) -> {
                synchronized (OneBotClient.this) {
                    if (!active(gen)) { if (ws != null) ws.abort(); return; }
                    if (error != null) reconnect(gen);
                    else socket = ws;
                }
            });
        } catch (RuntimeException e) { reconnect(gen); }
    }
    private synchronized boolean active(long gen) { return running && generation == gen; }
    private synchronized void reconnect(long gen) {
        if (!active(gen) || reconnectScheduled) return;
        reconnectScheduled = true;
        if (socket != null) { socket.abort(); socket = null; }
        log.fine("QQAuth OneBot disconnected; retry scheduled");
        scheduler.schedule(() -> { synchronized (OneBotClient.this) { if (active(gen)) connect(); } },
                Math.max(1, settings.reconnectDelay().toMillis()), TimeUnit.MILLISECONDS);
    }
    public synchronized void stop() { close(); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; running = false; ++generation;
        if (connecting != null) connecting.cancel(true);
        if (socket != null) { socket.abort(); socket = null; }
        scheduler.shutdownNow(); workers.shutdownNow();
        http.shutdownNow();
    }
    private final class Connection implements WebSocket.Listener {
        private final long gen;
        private final StringBuilder buffer = new StringBuilder();
        private CompletableFuture<WebSocket> sends;
        private Connection(long gen) { this.gen = gen; }
        @Override public void onOpen(WebSocket ws) {
            synchronized (OneBotClient.this) {
                if (!active(gen)) { ws.abort(); return; }
                socket = ws;
            }
            ws.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (!active(gen)) { ws.abort(); return null; }
            if (data.length() > settings.maxMessageChars() - buffer.length()) {
                buffer.setLength(0); ws.abort(); reconnect(gen); return null;
            }
            buffer.append(data);
            if (!last) { ws.request(1); return null; }
            String payload = buffer.toString(); buffer.setLength(0);
            // Acknowledging demand only after processing/sending bounds the outbound queue too.
            CompletableFuture<Void> completed = new CompletableFuture<>();
            try {
                workers.execute(() -> {
                    try {
                        if (!active(gen)) return;
                        GroupProtocol.Message message;
                        try { message = GroupProtocol.oneBot(payload); }
                        catch (RuntimeException e) { return; }
                        if (message == null) return;
                        GroupResult result = processor.handle(message.group(), message.sender(), message.text());
                        if (result != null && result.handled() && !result.reply().isEmpty() && active(gen)) {
                            sends = ws.sendText(GroupProtocol.oneBotReply(message.group(), result.reply()), true);
                            sends.orTimeout(10, TimeUnit.SECONDS).join();
                        }
                    } catch (RuntimeException e) {
                        log.warning("QQAuth OneBot message processing failed (" + e.getClass().getSimpleName() + ")");
                        // A failed send must not leave a connection with unbounded pending writes.
                        if (sends != null && sends.isCompletedExceptionally()) { ws.abort(); reconnect(gen); }
                    } finally { completed.complete(null); if (active(gen)) ws.request(1); }
                });
            } catch (RejectedExecutionException e) { ws.abort(); reconnect(gen); completed.complete(null); }
            return completed;
        }
        @Override public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ws.abort(); reconnect(gen); return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) { ws.request(1); return ws.sendPong(message); }
        @Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) { ws.request(1); return null; }
        @Override public CompletionStage<?> onClose(WebSocket ws, int status, String reason) { reconnect(gen); return null; }
        @Override public void onError(WebSocket ws, Throwable error) { reconnect(gen); }
    }
}
