package com.lunarboreal.permissions;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only import of the former permission document. */
final class LegacyPermissions {
    private static final String LEGACY_PREFIX = "l" + "br.";

    record Entry(String key, boolean value, long expiresAt, boolean inheritance) {}

    static PermissionState read(Path file) throws IOException {
        try {
            PermissionState state = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), PermissionState.class);
            if (state == null) throw new IllegalArgumentException("文件为空");
            return state;
        } catch (RuntimeException ex) {
            throw new IOException("旧权限文件读取失败：" + ex.getMessage(), ex);
        }
    }

    static String currentNode(String node) {
        return node.startsWith(LEGACY_PREFIX) ? "lc." + node.substring(LEGACY_PREFIX.length()) : node;
    }

    static List<Entry> grants(Map<String, PermissionState.Grant> grants, long now) {
        var entries = new ArrayList<Entry>();
        grants.forEach((key, grant) -> {
            if (grant.active(now)) entries.add(new Entry(currentNode(key), grant.value(), grant.expiresAt(), false));
        });
        return entries;
    }

    static List<Entry> memberships(Map<String, PermissionState.Membership> memberships, long now) {
        var entries = new ArrayList<Entry>();
        memberships.forEach((key, membership) -> {
            if (membership.active(now)) entries.add(new Entry(key, true, membership.expiresAt(), true));
        });
        return entries;
    }
}
