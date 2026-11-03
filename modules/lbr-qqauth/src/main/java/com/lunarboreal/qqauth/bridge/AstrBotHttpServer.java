/* Copyright (c) 2026 QQAuth contributors. SPDX-License-Identifier: MIT
 * Adapted from QQAuth AstrBotHttpServer; see bridge/LICENSE. */
package com.lunarboreal.qqauth.bridge;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** Authenticated bridge boundary. The token holder is trusted to attest group/sender IDs. */
public final class AstrBotHttpServer implements AutoCloseable {
    private final AstrBotSettings settings;
    private final GroupProcessor processor;
    private final Logger log;
    private final byte[] tokenHash;
    private HttpServer server;
    private ThreadPoolExecutor executor;
    private boolean closed;
    public AstrBotHttpServer(Logger log, AstrBotSettings settings, GroupProcessor processor) {
        this.log = Objects.requireNonNull(log); this.settings = Objects.requireNonNull(settings);
        this.processor = Objects.requireNonNull(processor); tokenHash = digest(settings.token());
    }
    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("Bridge closed");
        if (server != null) return;
        HttpServer created = HttpServer.create(new InetSocketAddress(settings.bindAddress(), settings.port()), settings.queueCapacity());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(settings.workers(), settings.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), r -> { Thread t = new Thread(r, "QQAuth-HTTP"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
        try {
            created.createContext("/qqauth/group", this::handle);
            created.setExecutor(pool); created.start(); server = created; executor = pool;
        } catch (RuntimeException e) { created.stop(0); pool.shutdownNow(); throw e; }
    }
    public synchronized int port() { return server == null ? -1 : server.getAddress().getPort(); }
    public synchronized void stop() { close(); }
    @Override public synchronized void close() {
        closed = true;
        if (server != null) { server.stop(0); server = null; }
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"/qqauth/group".equals(exchange.getRequestURI().getPath())) { error(exchange, 404, "not-found"); return; }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST"); error(exchange, 405, "method-not-allowed"); return;
            }
            String supplied = exchange.getRequestHeaders().getFirst("X-QQAuth-Token");
            if (supplied == null || supplied.isBlank()) {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) supplied = authorization.substring(7).trim();
            }
            if (supplied == null || !MessageDigest.isEqual(tokenHash, digest(supplied))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer"); error(exchange, 401, "unauthorized"); return;
            }
            GroupProtocol.Message message;
            try {
                byte[] data = exchange.getRequestBody().readNBytes(settings.maxBodyBytes() + 1);
                if (data.length > settings.maxBodyBytes()) { error(exchange, 413, "body-too-large"); return; }
                message = GroupProtocol.astrBot(new String(data, StandardCharsets.UTF_8));
            } catch (RuntimeException e) { error(exchange, 400, "bad-request"); return; }
            try {
                GroupResult result = Objects.requireNonNull(processor.handle(message.group(), message.sender(), message.text()));
                send(exchange, 200, GroupProtocol.reply(result));
            } catch (RuntimeException e) {
                log.warning("QQAuth bridge processor failed (" + e.getClass().getSimpleName() + ")"); error(exchange, 500, "internal-error");
            }
        } finally { exchange.close(); }
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void error(HttpExchange exchange, int status, String code) throws IOException { send(exchange, status, "{\"ok\":false,\"error\":\"" + code + "\"}"); }
    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
