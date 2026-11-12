package com.lunarboreal.chat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 玩家聊天数据（内存缓存）。 */
final class ChatUser {
    final UUID uuid;
    volatile String nick;           // MiniMessage/& 原始文本，null 表示无昵称
    final Set<UUID> ignored = ConcurrentHashMap.newKeySet();
    volatile boolean socialSpy;
    volatile boolean msgDisabled;

    // ------- 不持久化 -------
    volatile boolean staffChat;
    volatile UUID lastMessaged;
    volatile long lastChatAt;
    volatile String lastMessage = "";
    volatile long lastMessageAt;

    ChatUser(UUID uuid) {
        this.uuid = uuid;
    }

    String ignoredString() {
        StringBuilder sb = new StringBuilder();
        for (UUID u : ignored) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(u);
        }
        return sb.toString();
    }

    void parseIgnored(String s) {
        ignored.clear();
        if (s == null || s.isBlank()) return;
        for (String part : s.split(",")) {
            try { ignored.add(UUID.fromString(part.trim())); } catch (IllegalArgumentException ignored2) { }
        }
    }
}
