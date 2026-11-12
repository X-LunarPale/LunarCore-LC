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

    PapiBridge(LunarCorePlugin plugin, LcPlaceholderRegistry registry) {
        this.expansion = new Expansion(plugin, registry, "lc");
    }

    boolean hook() {
        return expansion.register();
    }

    void unhook() {
        if (expansion.isRegistered()) expansion.unregister();
    }

    String apply(OfflinePlayer player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }

    private static final class Expansion extends PlaceholderExpansion {
        private final LunarCorePlugin plugin;
        private final LcPlaceholderRegistry registry;
        private final String identifier;

        Expansion(LunarCorePlugin plugin, LcPlaceholderRegistry registry, String identifier) {
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
