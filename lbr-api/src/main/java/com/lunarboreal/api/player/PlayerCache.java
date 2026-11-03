package com.lunarboreal.api.player;

import java.util.Optional;
import java.util.UUID;

/**
 * core 维护的玩家档案（表 lbr_players）：首次/最后登录时间、最后 IP、最后名字。
 * 内存缓存，可在主线程调用。
 */
public interface PlayerCache {

    record PlayerRecord(UUID uuid, String name, long firstJoin, long lastJoin, long lastQuit,
                        String lastIp, long playTimeMillis) {}

    Optional<PlayerRecord> get(UUID uuid);

    /** 忽略大小写。 */
    Optional<PlayerRecord> getByName(String name);

    default Optional<UUID> uuidOf(String name) { return getByName(name).map(PlayerRecord::uuid); }

    default String nameOf(UUID uuid) { return get(uuid).map(PlayerRecord::name).orElse(uuid.toString()); }
}
