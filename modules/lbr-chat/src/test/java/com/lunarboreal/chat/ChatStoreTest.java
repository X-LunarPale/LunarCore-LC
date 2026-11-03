package com.lunarboreal.chat;

import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.database.SqlFunction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ChatStoreTest {
    @Test
    void boundsIncomingLogsAndReportsDiscardedRows() {
        RecordingDatabase database = new RecordingDatabase();
        List<String> warnings = new CopyOnWriteArrayList<>();
        ChatStore store = new ChatStore(database.proxy(), logger(warnings));
        for (int i = 0; i < ChatStore.MAX_PENDING_LOGS + 8; i++) queue(store, "message-" + i);

        store.flushLogs();
        assertEquals(ChatStore.MAX_PENDING_LOGS, database.committed.size());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("8 条记录")));
        store.flushLogs();
        assertEquals(ChatStore.MAX_PENDING_LOGS, database.committed.size());
        assertEquals(1, database.attempts.get());
    }

    @Test
    void failedTransactionRetriesOnceWithinTheSameQueueLimit() {
        RecordingDatabase database = new RecordingDatabase();
        List<String> warnings = new CopyOnWriteArrayList<>();
        ChatStore store = new ChatStore(database.proxy(), logger(warnings));
        for (int i = 0; i < 3000; i++) queue(store, "old-" + i);
        database.beforeCommit = () -> {
            database.beforeCommit = null;
            for (int i = 0; i < 3000; i++) queue(store, "new-" + i);
            throw new SQLException("simulated database outage");
        };

        store.flushLogs();
        assertTrue(database.committed.isEmpty());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("1000 条记录")));
        store.flushLogs();
        assertEquals(ChatStore.MAX_PENDING_LOGS, database.committed.size());
        assertEquals(ChatStore.MAX_PENDING_LOGS, new HashSet<>(database.committed).size());
        assertTrue(database.committed.contains("old-0"));
        assertTrue(database.committed.contains("old-1999"));
        assertFalse(database.committed.contains("old-2000"));
        assertTrue(database.committed.contains("new-2999"));
        store.flushLogs();
        assertEquals(2, database.attempts.get());
    }

    @Test
    void concurrentFlushesNeverOverlapTransactionsOrDuplicateRows() throws Exception {
        RecordingDatabase database = new RecordingDatabase();
        ChatStore store = new ChatStore(database.proxy(), logger(new CopyOnWriteArrayList<>()));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        database.beforeCommit = () -> {
            database.beforeCommit = null;
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new SQLException("test timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
        };
        var callers = Executors.newFixedThreadPool(2);
        try {
            queue(store, "first");
            var first = callers.submit(store::flushLogs);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            queue(store, "second");
            var second = callers.submit(() -> {
                secondStarted.countDown();
                store.flushLogs();
            });
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(1, database.maxConcurrent.get());
            assertEquals(List.of("first", "second"), database.committed);
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static void queue(ChatStore store, String message) {
        store.queueLog(null, "player", "public", message);
    }

    private static Logger logger(List<String> warnings) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { warnings.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }

    @FunctionalInterface
    private interface BeforeCommit {
        void run() throws SQLException;
    }

    private static final class RecordingDatabase {
        final List<String> committed = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        volatile BeforeCommit beforeCommit;

        Database proxy() {
            return proxy(Database.class, (method, args) -> switch (method) {
                case "table" -> "lbr_" + args[0];
                case "transaction" -> transaction(args[0]);
                default -> throw new AssertionError("Unexpected database method: " + method);
            });
        }

        @SuppressWarnings("unchecked")
        private Object transaction(Object work) throws SQLException {
            attempts.incrementAndGet();
            maxConcurrent.accumulateAndGet(active.incrementAndGet(), Math::max);
            List<String> batch = new ArrayList<>();
            Object[] row = new Object[5];
            PreparedStatement statement = proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "setLong", "setString" -> {
                    row[(Integer) args[0] - 1] = args[1];
                    yield null;
                }
                case "addBatch" -> { batch.add((String) row[4]); yield null; }
                case "executeBatch" -> new int[batch.size()];
                case "close" -> null;
                default -> throw new AssertionError("Unexpected statement method: " + method);
            });
            Connection connection = proxy(Connection.class, (method, args) -> switch (method) {
                case "prepareStatement" -> statement;
                default -> throw new AssertionError("Unexpected connection method: " + method);
            });
            try {
                Object result = ((SqlFunction<Connection, ?>) work).apply(connection);
                BeforeCommit hook = beforeCommit;
                if (hook != null) hook.run();
                committed.addAll(batch);
                return result;
            } finally {
                active.decrementAndGet();
            }
        }

        @FunctionalInterface
        private interface Invocation {
            Object call(String method, Object[] args) throws Throwable;
        }

        private static <T> T proxy(Class<T> type, Invocation invocation) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (object, method, args) -> invocation.call(method.getName(), args)));
        }
    }
}
