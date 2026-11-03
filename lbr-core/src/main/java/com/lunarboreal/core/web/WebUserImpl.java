package com.lunarboreal.core.web;

import com.lunarboreal.api.web.WebUser;

import java.util.Collection;
import java.util.Set;

/** 已认证的 Web 用户。jti/expiresAt/tokenVersion 来自 JWT。 */
record WebUserImpl(String username, String role, Set<String> permissions, String jti, long expiresAt, int tokenVersion)
        implements WebUser {

    @Override
    public boolean hasPermission(String node) {
        return matchesAny(permissions, node);
    }

    /** 是否拥有全部权限（角色含 "*" 且无任何拒绝项）。 */
    boolean isSuper() {
        return permissions.contains("*") && permissions.stream().noneMatch(p -> p.startsWith("-"));
    }

    /** 节点匹配：支持 "*"、"economy.*"、"*.view" 等通配（* 匹配任意字符）；"-node" 表示显式拒绝。 */
    static boolean matchesAny(Collection<String> patterns, String node) {
        if (node == null || node.isEmpty()) return true;
        boolean allowed = false;
        for (String p : patterns) {
            if (p == null || p.isEmpty()) continue;
            if (p.startsWith("-")) {
                if (glob(p.substring(1), node)) return false;
            } else if (!allowed && glob(p, node)) {
                allowed = true;
            }
        }
        return allowed;
    }

    static boolean glob(String pattern, String node) {
        if (pattern.equals("*") || pattern.equals(node)) return true;
        if (!pattern.contains("*")) return false;
        // economy.* 同时匹配 economy 本身
        if (pattern.endsWith(".*") && node.equals(pattern.substring(0, pattern.length() - 2))) return true;
        return globMatch(pattern, 0, node, 0);
    }

    private static boolean globMatch(String p, int pi, String s, int si) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                if (pi == p.length() - 1) return true;
                for (int k = si; k <= s.length(); k++) {
                    if (globMatch(p, pi + 1, s, k)) return true;
                }
                return false;
            }
            if (si >= s.length() || s.charAt(si) != c) return false;
            pi++;
            si++;
        }
        return si == s.length();
    }
}
