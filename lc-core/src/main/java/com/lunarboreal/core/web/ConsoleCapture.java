package com.lunarboreal.core.web;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 挂在 log4j root logger 上的 Appender，缓存最近日志并推送到 Web 控制台。 */
final class ConsoleCapture {

    private static final int MAX_LINES = 1000;
    private static final int MAX_MESSAGE_CHARS = 8192;
    private static final String TRUNCATED = "... [truncated]";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Pattern LEGACY = Pattern.compile("§[0-9a-fk-orx]", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ArrayDeque<Map<String, Object>> buffer = new ArrayDeque<>();
    private final Consumer<Map<String, Object>> sink;
    private Appender appender;
    /** 需要在 Web 控制台中脱敏的字符串（如刚生成的管理员密码）。 */
    private final java.util.Set<String> secrets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    void addSecret(String secret) {
        if (secret == null || secret.length() < 4) return;
        secrets.add(secret);
        synchronized (buffer) {
            for (Map<String, Object> line : buffer) {
                Object m = line.get("message");
                if (m instanceof String s && s.contains(secret)) line.put("message", s.replace(secret, "******"));
            }
        }
    }

    ConsoleCapture(Consumer<Map<String, Object>> sink) {
        this.sink = sink;
    }

    void install() {
        try {
            Logger root = (Logger) LogManager.getRootLogger();
            appender = new Appender();
            appender.start();
            root.addAppender(appender);
        } catch (Throwable t) {
            appender = null;
            org.bukkit.Bukkit.getLogger().warning("[LunarCore] Web 控制台日志捕获不可用：" + t);
        }
    }

    void uninstall() {
        if (appender == null) return;
        try {
            Logger root = (Logger) LogManager.getRootLogger();
            root.removeAppender(appender);
            appender.stop();
        } catch (Throwable ignored) {
        }
        appender = null;
    }

    List<Map<String, Object>> history() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    private void accept(LogEvent event) {
        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        // 避免 Web 服务器自身的发送日志造成回环
        if (logger.startsWith("org.eclipse.jetty") || logger.startsWith("io.javalin")) return;
        String msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        if (msg == null) msg = "";
        if (event.getThrown() != null) {
            StringBuilder sb = new StringBuilder(msg);
            Throwable t = event.getThrown();
            sb.append('\n').append(t);
            for (StackTraceElement el : t.getStackTrace()) {
                sb.append("\n\tat ").append(el);
                if (sb.length() > 8000) break;
            }
            msg = sb.toString();
        }
        msg = LEGACY.matcher(ANSI.matcher(msg).replaceAll("")).replaceAll("");
        for (String s : secrets) if (msg.contains(s)) msg = msg.replace(s, "******");
        // Bound retained web history after redaction; the original server log is unaffected.
        if (msg.length() > MAX_MESSAGE_CHARS) {
            int end = MAX_MESSAGE_CHARS - TRUNCATED.length();
            if (Character.isHighSurrogate(msg.charAt(end - 1))) end--;
            msg = msg.substring(0, end) + TRUNCATED;
        }
        long time = event.getTimeMillis();
        Map<String, Object> line = Json.of(
                "time", time,
                "timeText", TIME.format(Instant.ofEpochMilli(time)),
                "level", event.getLevel() == null ? "INFO" : event.getLevel().name(),
                "logger", shortLogger(logger),
                "thread", event.getThreadName(),
                "message", msg);
        synchronized (buffer) {
            buffer.addLast(line);
            while (buffer.size() > MAX_LINES) buffer.removeFirst();
        }
        sink.accept(line);
    }

    private static String shortLogger(String name) {
        if (name.isEmpty()) return "";
        int i = name.lastIndexOf('.');
        return i >= 0 && i < name.length() - 1 && Character.isLowerCase(name.charAt(0)) ? name.substring(i + 1) : name;
    }

    private final class Appender extends AbstractAppender {
        Appender() {
            super("LunarCoreWebConsole", (Filter) null, null, false, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            try {
                accept(event.toImmutable());
            } catch (Throwable ignored) {
            }
        }
    }
}
