package com.lunarboreal.essentials.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 玩家持久化状态（以 JSON 存入 lbr_essentials_users.data）。 */
public final class UserData {

    public transient UUID uuid;
    public transient volatile boolean dirty;
    /** 是否已从数据库加载（get() 创建的占位数据为 false） */
    public transient volatile boolean loaded;

    public boolean god;
    public boolean fly;
    public boolean vanish;
    /** 物品类型(Material 名) → 绑定的命令列表 */
    public Map<String, List<String>> powertools = new LinkedHashMap<>();
    /** kit 名 → 上次领取时间（毫秒） */
    public Map<String, Long> kitUses = new LinkedHashMap<>();
    /** 已领取过的一次性礼包 */
    public Set<String> kitOnce = new HashSet<>();
    /** 是否已发放首次进服礼包 */
    public boolean firstKitsGiven;

    public void normalize() {
        if (powertools == null) powertools = new LinkedHashMap<>();
        if (kitUses == null) kitUses = new LinkedHashMap<>();
        if (kitOnce == null) kitOnce = new HashSet<>();
        powertools.replaceAll((k, v) -> v == null ? new ArrayList<>() : new ArrayList<>(v));
    }

    public void markDirty() {
        dirty = true;
    }
}
