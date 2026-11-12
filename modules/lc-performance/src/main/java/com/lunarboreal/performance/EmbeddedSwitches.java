package com.lunarboreal.performance;

import com.lunarboreal.api.web.HttpException;
import org.bukkit.configuration.ConfigurationSection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit web-editable feature switches. Limits, worlds and data-protection policies remain untouched. */
final class EmbeddedSwitches {
    record Switch(String key, String label, String parent) {
        String path() { return "embedded." + key; }
    }
    static final List<Switch> ALL = List.of(
        top("lag-shield", "低 TPS 负载保护"), top("redstone", "红石与活塞优化"), top("explosion", "爆炸优化"),
        top("leaf-decay", "加速树叶衰减"), top("hopper", "漏斗优化"), top("entity-limiter", "实体限额"),
        top("world-cleaner", "世界清理"), top("trash-disposal", "垃圾箱"), top("ability-limiter", "玩家能力优化"),
        top("afk", "挂机优化"), top("mob-ai", "生物智能优化"), top("vehicle", "载具优化"), top("console-filter", "控制台日志过滤"),
        child("lag-shield.pause-spawns", "低 TPS 负载保护：暂停生物生成", "lag-shield.enabled"),
        child("lag-shield.pause-hoppers", "低 TPS 负载保护：暂停漏斗传输", "lag-shield.enabled"),
        child("lag-shield.pause-redstone", "低 TPS 负载保护：暂停红石", "lag-shield.enabled"),
        child("lag-shield.pause-liquids", "低 TPS 负载保护：暂停液体流动", "lag-shield.enabled"),
        child("lag-shield.pause-projectiles", "低 TPS 负载保护：暂停投射物", "lag-shield.enabled"),
        child("explosion.anti_chain.prevent_block_ignition", "爆炸：阻止方块引燃连锁炸药", "explosion.anti_chain.enabled"),
        child("entity-limiter.overflow-purge.types.creatures", "超限清理：生物", "entity-limiter.overflow-purge.enabled"),
        child("entity-limiter.overflow-purge.types.items", "超限清理：掉落物", "entity-limiter.overflow-purge.enabled"),
        child("entity-limiter.overflow-purge.types.vehicles", "超限清理：载具", "entity-limiter.overflow-purge.enabled"),
        child("entity-limiter.overflow-purge.types.projectiles", "超限清理：投射物", "entity-limiter.overflow-purge.enabled"),
        child("trash-disposal.forced-trash", "垃圾箱：禁止直接丢弃物品", "trash-disposal.enabled"),
        child("lag-shield.dynamic-distances", "低 TPS 负载保护：动态视距", "lag-shield.enabled"),
        child("explosion.yield_limit.enabled", "爆炸：范围与掉落限制", "explosion.enabled"),
        child("explosion.anti_chain.enabled", "爆炸：连锁限制", "explosion.enabled"),
        child("hopper.smart_throttling.enabled", "漏斗：智能传输节流", "hopper.enabled"),
        child("hopper.chunk_limiting.enabled", "漏斗：区块传输限额", "hopper.enabled"),
        child("entity-limiter.overflow-purge.enabled", "实体：超限清理", "entity-limiter.enabled"),
        child("world-cleaner.items.enabled", "世界清理：掉落物", "world-cleaner.enabled"),
        child("world-cleaner.items.abyss.enabled", "世界清理：物品回收箱", "world-cleaner.items.enabled"),
        child("world-cleaner.creatures.enabled", "世界清理：生物", "world-cleaner.enabled"),
        child("world-cleaner.projectiles.enabled", "世界清理：投射物", "world-cleaner.enabled"),
        child("trash-disposal.cleanup.enabled", "垃圾箱：定时清空", "trash-disposal.enabled"),
        child("afk.kick.enabled", "挂机：超时移出", "afk.enabled"),
        child("afk.teleport.enabled", "挂机：定点传送", "afk.enabled"),
        child("afk.hide-entities.enabled", "挂机：隐藏周围实体", "afk.enabled"),
        child("mob-ai.animals.breed.enabled", "生物智能：繁殖优化", "mob-ai.enabled"),
        child("mob-ai.animals.tempt.enabled", "生物智能：诱食优化", "mob-ai.enabled"),
        child("vehicle.boat.enabled", "载具：船只优化", "vehicle.enabled"),
        child("vehicle.minecart.enabled", "载具：矿车优化", "vehicle.enabled")
    );
    private static Switch top(String id, String label) { return new Switch(id + ".enabled", label, null); }
    private static Switch child(String key, String label, String parent) { return new Switch(key, label, parent); }
    static Map<String, Object> values(ConfigurationSection config) {
        Map<String, Object> values = new LinkedHashMap<>();
        ALL.forEach(s -> values.put(s.key(), config.getBoolean(s.path(), false)));
        return values;
    }
    static Map<String, Object> changes(Map<String, Object> body, ConfigurationSection config) {
        if (body == null || body.isEmpty()) throw HttpException.badRequest("请选择需要修改的功能开关");
        Map<String, Object> changes = new LinkedHashMap<>();
        for (var entry : body.entrySet()) {
            Switch feature = ALL.stream().filter(s -> s.key().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> HttpException.badRequest("包含未知功能开关"));
            if (!(entry.getValue() instanceof Boolean)) throw HttpException.badRequest(feature.label() + "必须为开关值");
            changes.put(feature.path(), entry.getValue());
        }
        // Only newly enabled child features activate parents; unchanged children do not undo an explicit module shutdown.
        for (Switch feature : ALL) if (Boolean.TRUE.equals(body.get(feature.key())) && !config.getBoolean(feature.path(), false)) {
            String parent = feature.parent();
            while (parent != null) {
                changes.put("embedded." + parent, true);
                String key = parent;
                parent = ALL.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow().parent();
            }
        }
        return changes;
    }
    static Map<String, Object> batch(boolean enabled) {
        Map<String, Object> changes = new LinkedHashMap<>();
        ALL.forEach(s -> changes.put(s.path(), enabled));
        return changes;
    }
    static String label(String id) {
        return ALL.stream().filter(s -> s.key().equals(id + ".enabled")).findFirst().map(Switch::label).orElse("内置优化");
    }
}
