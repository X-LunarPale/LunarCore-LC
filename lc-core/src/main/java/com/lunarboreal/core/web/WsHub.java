package com.lunarboreal.core.web;

import io.javalin.websocket.WsContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * WebSocket /api/ws?token=... 连接管理。
 * <pre>
 * 客户端 → {"op":"sub","topic":"console"} / {"op":"unsub","topic":"console"} / {"op":"ping"}
 * 服务端 → {"topic":"console","data":{...}} / {"topic":"_backlog","data":{"topic":"console","items":[..]}}
 * </pre>
 */
final class WsHub {

    static final class Client {
        final WsContext ctx;
        volatile WebUserImpl user;
        final Set<String> topics = ConcurrentHashMap.newKeySet();

        Client(WsContext ctx, WebUserImpl user) {
            this.ctx = ctx;
            this.user = user;
        }
    }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    /** topic → 订阅所需权限。 */
    private final Function<String, String> topicPermission;
    private volatile ThreadPoolExecutor sender;

    WsHub(Function<String, String> topicPermission) {
        this.topicPermission = topicPermission;
    }

    void start() {
        sender = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(5000), r -> {
            Thread t = new Thread(r, "LunarCore-Web-WS");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());
    }

    void stop() {
        for (Client c : clients.values()) {
            try {
                c.ctx.closeSession(1001, "server stopping");
            } catch (Throwable ignored) {
            }
        }
        clients.clear();
        if (sender != null) sender.shutdownNow();
        sender = null;
    }

    int size() {
        return clients.size();
    }

    void add(WsContext ctx, WebUserImpl user) {
        clients.put(ctx.sessionId(), new Client(ctx, user));
    }

    void remove(WsContext ctx) {
        clients.remove(ctx.sessionId());
    }

    Client get(WsContext ctx) {
        return clients.get(ctx.sessionId());
    }

    boolean canSubscribe(WebUserImpl user, String topic) {
        String perm = topicPermission.apply(topic);
        return perm == null || user.hasPermission(perm);
    }

    void send(Client c, Object message) {
        send(c, null, message);
    }

    void send(Client c, String topic, Object message) {
        ThreadPoolExecutor ex = sender;
        if (ex == null || c == null || clients.get(c.ctx.sessionId()) != c) return;
        if (topic != null && (!c.topics.contains(topic) || !canSubscribe(c.user, topic))) return;
        String text = Json.GSON.toJson(message);
        ex.execute(() -> sendNow(c, text, topic));
    }

    void broadcast(String topic, Object payload) {
        ThreadPoolExecutor ex = sender;
        if (ex == null || !hasSubscribers(topic)) return;
        ex.execute(() -> {
            String text = null;
            long now = System.currentTimeMillis();
            for (Client c : clients.values()) {
                if (!c.topics.contains(topic)) continue;
                if (c.user.expiresAt() > 0 && c.user.expiresAt() < now) {
                    try { c.ctx.closeSession(4001, "token expired"); } catch (Throwable ignored) {}
                    clients.remove(c.ctx.sessionId());
                    continue;
                }
                if (text == null) text = Json.GSON.toJson(Json.of("topic", topic, "data", payload));
                sendNow(c, text, topic);
            }
        });
    }

    private boolean hasSubscribers(String topic) {
        for (Client c : clients.values()) {
            if (c.topics.contains(topic)) return true;
        }
        return false;
    }

    /** 用户被删除或角色变更时断开/刷新。 */
    void refreshUsers(Function<WebUserImpl, WebUserImpl> mapper) {
        for (Client c : clients.values()) {
            WebUserImpl n = mapper.apply(c.user);
            if (n == null) {
                try { c.ctx.closeSession(4001, "unauthorized"); } catch (Throwable ignored) {}
                clients.remove(c.ctx.sessionId());
            } else {
                c.user = n;
                c.topics.removeIf(t -> !canSubscribe(n, t));
            }
        }
    }

    private void sendNow(Client c, String text, String topic) {
        try {
            if (clients.get(c.ctx.sessionId()) != c) return;
            if (topic != null && (!c.topics.contains(topic) || !canSubscribe(c.user, topic))) return;
            if (c.ctx.session.isOpen()) c.ctx.send(text);
        } catch (Throwable t) {
            clients.remove(c.ctx.sessionId(), c);
        }
    }
}
