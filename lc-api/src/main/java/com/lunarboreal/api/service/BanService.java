package com.lunarboreal.api.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 封禁/禁言服务（由 ban 模块提供）。 */
public interface BanService {

    enum Type { BAN, IPBAN, MUTE, WARN, KICK }

    /**
     * @param expiresAt 毫秒时间戳，null 表示永久
     */
    record Punishment(long id, Type type, UUID target, String targetName, String ip,
                      String reason, String operator, long createdAt, Long expiresAt,
                      boolean active, String revokedBy) {
        public boolean isPermanent() { return expiresAt == null; }
        public boolean isExpired() { return expiresAt != null && expiresAt < System.currentTimeMillis(); }
    }

    Optional<Punishment> activeBan(UUID player);

    Optional<Punishment> activeIpBan(String ip);

    Optional<Punishment> activeMute(UUID player);

    default boolean isMuted(UUID player) { return activeMute(player).isPresent(); }

    Punishment punish(Type type, UUID target, String targetName, String ip, String reason, String operator, Long durationMillis);

    boolean revoke(long punishmentId, String operator);

    List<Punishment> history(UUID player);
}
