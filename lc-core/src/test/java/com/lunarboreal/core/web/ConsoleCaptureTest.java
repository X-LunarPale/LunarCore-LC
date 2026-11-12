package com.lunarboreal.core.web;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleCaptureTest {
    @Test void oversizedMessagesAreBoundedInBothHistoryAndBroadcast() throws Exception {
        var sent = new ArrayList<Map<String, Object>>();
        var capture = new ConsoleCapture(sent::add);
        accept(capture, "a".repeat(2 * 1024 * 1024), null);

        String message = (String) capture.history().getFirst().get("message");
        assertEquals(8192, message.length());
        assertTrue(message.endsWith("... [truncated]"));
        assertSame(capture.history().getFirst(), sent.getFirst());
    }

    @Test void secretsAreRedactedBeforeTruncatingAtTheBoundary() throws Exception {
        var capture = new ConsoleCapture(line -> {});
        capture.addSecret("private-test-secret");
        accept(capture, "a".repeat(8172) + "private-test-secret" + "b".repeat(100), null);

        String message = (String) capture.history().getFirst().get("message");
        assertFalse(message.contains("private"));
        assertTrue(message.contains("*****"));
        assertTrue(message.endsWith("... [truncated]"));
    }

    @Test void ordinaryFormattingAndBoundedExceptionDetailsRemainAvailable() throws Exception {
        var capture = new ConsoleCapture(line -> {});
        accept(capture, "\u001b[31mcolored\u001b[0m", new IllegalStateException("failure"));

        String message = (String) capture.history().getFirst().get("message");
        assertTrue(message.startsWith("colored\njava.lang.IllegalStateException: failure"));
        assertTrue(message.contains("\n\tat "));
        assertFalse(message.contains("\u001b"));
    }

    @Test void truncationKeepsSupplementaryCharactersIntact() throws Exception {
        var capture = new ConsoleCapture(line -> {});
        String prefix = "a".repeat(8176);
        accept(capture, prefix + "\ud83d\ude00" + "b".repeat(100), null);

        assertEquals(prefix + "... [truncated]", capture.history().getFirst().get("message"));
    }

    private static void accept(ConsoleCapture capture, String message, Throwable thrown) throws Exception {
        LogEvent event = Log4jLogEvent.newBuilder()
                .setLoggerName("test.console")
                .setLevel(Level.INFO)
                .setTimeMillis(1)
                .setThreadName("test")
                .setMessage(new SimpleMessage(message))
                .setThrown(thrown)
                .build();
        Method accept = ConsoleCapture.class.getDeclaredMethod("accept", LogEvent.class);
        accept.setAccessible(true);
        accept.invoke(capture, event);
    }
}
