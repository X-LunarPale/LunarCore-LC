package com.lunarboreal.core.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 从类加载器读取静态资源（防目录穿越），带简单内存缓存。 */
final class StaticFiles {

    record Resource(byte[] data, String contentType) {}

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("wasm", "application/wasm"));

    private final Map<String, Resource> cache = new ConcurrentHashMap<>();

    /**
     * 规范化相对路径：去掉开头斜杠、拒绝 ".."、反斜杠、空字节等。
     * @return null 表示非法
     */
    static String safePath(String rel) {
        if (rel == null) return "";
        String p = rel.replace('\\', '/');
        if (p.indexOf('\0') >= 0 || p.contains("%")) return null;
        while (p.startsWith("/")) p = p.substring(1);
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return null;
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    /** 最后一段带扩展名才视为文件。 */
    static boolean looksLikeFile(String path) {
        int slash = path.lastIndexOf('/');
        String last = path.substring(slash + 1);
        return last.lastIndexOf('.') > 0;
    }

    static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }

    Resource load(ClassLoader loader, String root, String safeRel, boolean useCache) {
        String full = root.isEmpty() ? safeRel : root + "/" + safeRel;
        String key = System.identityHashCode(loader) + ":" + full;
        if (useCache) {
            Resource r = cache.get(key);
            if (r != null) return r;
        }
        Resource r;
        try (InputStream in = loader.getResourceAsStream(full)) {
            r = in == null ? null : new Resource(in.readAllBytes(), contentType(full));
        } catch (IOException e) {
            r = null;
        }
        // Do not retain arbitrary attacker-controlled misses (or unbounded paths) forever.
        if (useCache && r != null && cache.size() < 1024) cache.put(key, r);
        return r;
    }

    void clear() {
        cache.clear();
    }
}
