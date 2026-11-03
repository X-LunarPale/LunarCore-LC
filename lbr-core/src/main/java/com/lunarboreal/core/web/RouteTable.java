package com.lunarboreal.core.web;

import com.lunarboreal.api.web.WebHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 自管路由表：核心 API 与扩展 API 共用，支持 {param} 路径参数。 */
final class RouteTable {

    /** permission == null 且 publicRoute == false 表示仅需登录。 */
    record Route(String method, String path, String[] segments, String permission, boolean publicRoute,
                 WebHandler handler, Object owner, String namespace) {

        int score() {
            int s = segments.length * 4;
            for (String seg : segments) if (!isParam(seg)) s += 2;
            return s;
        }
    }

    record Match(Route route, Map<String, String> params) {}

    private final List<Route> routes = new CopyOnWriteArrayList<>();

    void add(String method, String path, String permission, boolean publicRoute, WebHandler handler,
             Object owner, String namespace) {
        String norm = normalize(path);
        String m = method.toUpperCase(Locale.ROOT);
        // 同一方法+路径重复注册时后者覆盖前者
        routes.removeIf(r -> r.method.equals(m) && r.path.equals(norm));
        routes.add(new Route(m, norm, split(norm), permission, publicRoute, handler, owner, namespace));
    }

    void removeOwner(Object owner) {
        routes.removeIf(r -> r.owner == owner);
    }

    void removeNamespace(String namespace, Object owner) {
        routes.removeIf(r -> r.owner == owner && namespace.equals(r.namespace));
    }

    int size() {
        return routes.size();
    }

    /** @return null 表示没有匹配；若路径存在但方法不对，返回 Match(route=null)。 */
    Match match(String method, String path) {
        String[] segs = split(normalize(path));
        Route best = null;
        Map<String, String> bestParams = null;
        boolean pathExists = false;
        for (Route r : routes) {
            if (r.segments.length != segs.length) continue;
            Map<String, String> params = new HashMap<>();
            boolean ok = true;
            for (int i = 0; i < segs.length; i++) {
                String rs = r.segments[i];
                if (isParam(rs)) {
                    params.put(rs.substring(1, rs.length() - 1), decode(segs[i]));
                } else if (!rs.equals(segs[i])) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            pathExists = true;
            if (!r.method.equalsIgnoreCase(method)) continue;
            if (best == null || r.score() > best.score()) {
                best = r;
                bestParams = params;
            }
        }
        if (best != null) return new Match(best, bestParams);
        return pathExists ? new Match(null, Map.of()) : null;
    }

    private static boolean isParam(String seg) {
        return seg.length() > 2 && seg.startsWith("{") && seg.endsWith("}");
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        String p = path.replace('\\', '/');
        if (!p.startsWith("/")) p = "/" + p;
        p = p.replaceAll("/{2,}", "/");
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String[] split(String norm) {
        if (norm.equals("/")) return new String[0];
        return norm.substring(1).split("/");
    }
}
