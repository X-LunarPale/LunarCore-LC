package com.lunarboreal.core.web;

import io.javalin.websocket.WsContext;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WsHubTest {
    @Test void unsubscribedBroadcastBurstsDoNotQueueWork() throws Exception {
        var hub = new WsHub(topic -> null);
        var received = new AtomicInteger();
        hub.start();
        try {
            WsContext ctx = context(text -> received.incrementAndGet());
            hub.add(ctx, user());
            hub.get(ctx).topics.add("dashboard");
            for (int i = 0; i < 10_000; i++) hub.broadcast("console", Map.of("message", "line " + i));

            assertEquals(0, sender(hub).getTaskCount());
            assertTrue(sender(hub).getQueue().isEmpty());
            assertEquals(0, received.get());
        } finally {
            hub.stop();
        }
    }

    @Test void subscribedClientsStillReceiveTheExpectedEnvelope() throws Exception {
        var hub = new WsHub(topic -> null);
        var received = new AtomicReference<String>();
        var delivered = new CountDownLatch(1);
        hub.start();
        try {
            WsContext ctx = context(text -> {
                received.set(text);
                delivered.countDown();
            });
            hub.add(ctx, user());
            hub.get(ctx).topics.add("console");
            hub.broadcast("console", Map.of("message", "hello"));

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            Map<String, Object> envelope = Json.map(received.get());
            assertEquals("console", envelope.get("topic"));
            assertEquals(Map.of("message", "hello"), envelope.get("data"));
        } finally {
            hub.stop();
        }
    }

    @Test void removedClientsDoNotReceiveQueuedDirectMessages() throws Exception {
        var hub = new WsHub(topic -> null);
        var received = new AtomicInteger();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        hub.start();
        try {
            ThreadPoolExecutor sender = sender(hub);
            sender.execute(() -> {
                blocked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            WsContext ctx = context(text -> received.incrementAndGet());
            hub.add(ctx, user());
            hub.send(hub.get(ctx), Map.of("topic", "_backlog"));
            hub.remove(ctx);
            release.countDown();
            sender.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, received.get());
        } finally {
            release.countDown();
            hub.stop();
        }
    }

    @Test void queuedBacklogIsDiscardedAfterPermissionsAreRevoked() throws Exception {
        assertQueuedBacklogIsDiscarded(true);
    }

    @Test void queuedBacklogIsDiscardedAfterUnsubscribing() throws Exception {
        assertQueuedBacklogIsDiscarded(false);
    }

    private void assertQueuedBacklogIsDiscarded(boolean revokePermission) throws Exception {
        var hub = new WsHub(topic -> "core.console.view");
        var received = new AtomicInteger();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        hub.start();
        try {
            ThreadPoolExecutor sender = sender(hub);
            sender.execute(() -> {
                blocked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            WsContext ctx = context(text -> received.incrementAndGet());
            hub.add(ctx, user());
            WsHub.Client client = hub.get(ctx);
            client.topics.add("console");
            hub.send(client, "console", Map.of("topic", "_backlog", "data", "private log"));
            if (revokePermission) {
                hub.refreshUsers(old -> new WebUserImpl("test", "viewer", Set.of(), "test", 0, 0));
            } else {
                client.topics.remove("console");
            }
            release.countDown();
            sender.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, received.get());
        } finally {
            release.countDown();
            hub.stop();
        }
    }

    private static WebUserImpl user() {
        return new WebUserImpl("test", "admin", Set.of("*"), "test", 0, 0);
    }

    private static WsContext context(Consumer<String> receive) {
        RemoteEndpoint remote = (RemoteEndpoint) Proxy.newProxyInstance(
                RemoteEndpoint.class.getClassLoader(), new Class<?>[]{RemoteEndpoint.class}, (p, m, args) -> {
                    if (m.getName().equals("sendString")) receive.accept((String) args[0]);
                    return null;
                });
        Session session = (Session) Proxy.newProxyInstance(
                Session.class.getClassLoader(), new Class<?>[]{Session.class}, (p, m, args) -> switch (m.getName()) {
                    case "isOpen" -> true;
                    case "getRemote" -> remote;
                    default -> null;
                });
        return new WsContext("test-session", session) {};
    }

    private static ThreadPoolExecutor sender(WsHub hub) throws Exception {
        Field field = WsHub.class.getDeclaredField("sender");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(hub);
    }
}
