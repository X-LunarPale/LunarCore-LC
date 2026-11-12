package com.lunarboreal.permissions;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

interface PermissionBridge {
    record Page(long total, List<Map<String, Object>> items) {}

    String initialize(Path directory, boolean migrate) throws Exception;
    List<Map<String, Object>> groups();
    Page users(String query, int page, int size);
    UUID identify(String value);
    List<Map<String, Object>> effective(UUID id);
    Map<String, Object> check(UUID id, String node);
    void mutate(String scope, String target, String action, String key, String value, String duration);
}
