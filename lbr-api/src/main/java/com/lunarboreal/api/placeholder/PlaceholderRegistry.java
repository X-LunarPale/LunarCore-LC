package com.lunarboreal.api.placeholder;

import org.bukkit.OfflinePlayer;

import java.util.Map;

/**
 * 变量注册表。core 桥接 PlaceholderAPI（扩展标识 "lc"，兼容 "lbr"）。
 * 写法为 %lc_&lt;namespace&gt;_&lt;key&gt;%，例如 %lc_economy_balance%、%lc_signin_streak%。
 * 未安装 PlaceholderAPI 时也可在 LC 内部使用（如聊天格式、计分板）。
 */
public interface PlaceholderRegistry {

    @FunctionalInterface
    interface Resolver {
        /**
         * @param player 可能为 null（无玩家上下文）
         * @param key namespace 之后的部分，如 "balance"、"top_1_name"
         * @return null 表示未知变量
         */
        String resolve(OfflinePlayer player, String key);
    }

    /** owner 为 LbrModule 或 Plugin，卸载时自动注销。 */
    void register(String namespace, Resolver resolver, Object owner);

    void unregisterAll(Object owner);

    /** 解析 "economy_balance"，也接受带 lc_ 或旧前缀的标识。 */
    String resolve(OfflinePlayer player, String identifier);

    /** 替换文本中的 %lc_xxx%（以及安装了 PAPI 时的其它 PAPI 变量）。 */
    String apply(OfflinePlayer player, String text);

    /** 已注册的命名空间 → 示例 key 描述（供 web 面板展示）。 */
    Map<String, String> namespaces();
}
