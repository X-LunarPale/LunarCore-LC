package com.lunarboreal.core.placeholder;

import com.lunarboreal.core.LunarCorePlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI 桥接。<b>仅在检测到 PlaceholderAPI 插件已启用后才会被加载</b>，
 * 其它类不得直接引用 me.clip.* 类型，避免未安装 PAPI 时 NoClassDefFoundError。
 */
final class PapiBridge {

    private final Expansion expansion;
    private final Expansion legacyExpansion;
    private boolean legacyRegistered;

    PapiBridge(LunarCorePlugin plugin, LbrPlaceholderRegistry registry) {
        this.expansion = new Expansion(plugin, registry, "lc");
        this.legacyExpansion = new Expansion(plugin, registry, "lbr");
    }

    boolean hook() {
        if (!expansion.register()) return false;
        try {
            legacyRegistered = legacyExpansion.register();
            if (!legacyRegistered) {
                expansion.plugin.getLogger().warning("旧版变量扩展已被占用，已启用 %lc_*% 变量");
            }
        } catch (RuntimeException e) {
            expansion.plugin.getLogger().warning("旧版变量扩展注册失败: " + e);
        }
        return true;
    }

    void unhook() {
        if (expansion.isRegistered()) expansion.unregister();
        if (legacyRegistered) legacyExpansion.unregister();
        legacyRegistered = false;
    }

    String apply(OfflinePlayer player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    private static final class Expansion extends PlaceholderExpansion {
        private final LunarCorePlugin plugin;
        private final LbrPlaceholderRegistry registry;
        private final String identifier;

        Expansion(LunarCorePlugin plugin, LbrPlaceholderRegistry registry, String identifier) {
            this.plugin = plugin;
            this.registry = registry;
            this.identifier = identifier;
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public String getAuthor() {
            return "琉月";
        }

        @Override
        public String getVersion() {
            return plugin.version();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            return registry.resolve(player, params);
        }
    }
}
