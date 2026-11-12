package com.lunarboreal.example.addon;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.service.EconomyService;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/**
 * LunarCore 外部插件扩展示例。
 * <p>
 * 外部插件通过 {@link LC#get()} 拿到 API，注册时 owner 传本插件实例（{@code this}）。
 * 本插件被禁用/卸载时，LunarCore 会监听 PluginDisableEvent 自动注销它注册的
 * 命令、服务、Web 路由/面板/静态资源/权限、变量与语言包，因此 onDisable 不必手动清理
 * （这里仍演示了显式注销写法）。
 */
public final class ExampleAddonPlugin extends JavaPlugin {

    /** Web / 变量 / 语言包共用的命名空间：路由 /api/ext/example/...、变量 %lc_example_xxx%、消息 key example.xxx */
    public static final String NS = "example";

    private LunarBorealAPI api;
    private ClickStore store;

    @Override
    public void onEnable() {
        if (!LC.isLoaded()) {
            getLogger().severe("未检测到 LunarCore，插件停用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        api = LC.get();

        // 1) 语言包：jar 内 lang/zh_CN.yml、lang/en_US.yml
        api.messages().registerBundle(NS, getClassLoader());

        // 2) 服务：其它插件可 api.services().get(ClickService.class)
        store = new ClickStore(api.database(), getLogger());
        store.initAsync();
        api.services().register(ClickService.class, store, this);

        // 3) 命令（动态注册到 CommandMap，无需写进 plugin.yml）
        api.commands().register(this, new ExampleCommand(this));

        // 4) 变量 %lc_example_points% / %lc_example_rank% / %lc_example_top_<n>_name%
        api.placeholders().register(NS, this::placeholder, this);

        // 5) Web：权限节点、REST 路由、声明式面板、自定义静态页面
        new ExampleWeb(this).register();

        // 6) 使用 LunarCore 内置模块提供的服务（可选依赖：每次使用时 get，不要长期持有）
        api.services().get(EconomyService.class).ifPresent(eco ->
                getLogger().info("检测到经济服务，货币：" + eco.currencyPlural()));

        getLogger().info("LcExampleAddon 已启用，Web 面板见「示例扩展」");
    }

    @Override
    public void onDisable() {
        // 可选：LunarCore 会在 PluginDisableEvent 时自动执行以下清理
        if (api != null) {
            api.web().unregisterAll(this);
            api.services().unregisterAll(this);
            api.placeholders().unregisterAll(this);
            api.messages().unregisterBundle(NS);
        }
    }

    private String placeholder(OfflinePlayer player, String key) {
        if (key.equals("points")) return player == null ? null : String.valueOf(store.points(player.getUniqueId()));
        if (key.equals("total")) return String.valueOf(store.sum());
        if (key.equals("rank")) {
            if (player == null) return null;
            List<ClickService.Entry> top = store.top(Integer.MAX_VALUE);
            for (int i = 0; i < top.size(); i++) if (top.get(i).uuid().equals(player.getUniqueId())) return String.valueOf(i + 1);
            return "-";
        }
        if (key.startsWith("top_") && key.endsWith("_name")) {
            try {
                int n = Integer.parseInt(key.substring(4, key.length() - 5));
                List<ClickService.Entry> top = store.top(n);
                return n >= 1 && n <= top.size() ? top.get(n - 1).name() : "-";
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null; // 未知变量返回 null
    }

    public LunarBorealAPI api() { return api; }

    public ClickStore store() { return store; }

    /** 发送本插件语言包中的消息。 */
    public void msg(org.bukkit.command.CommandSender to, String key, Object... ph) {
        api.messages().send(to, NS + "." + key, ph);
    }

    public Map<String, Object> ph(Object... kv) {
        return com.lunarboreal.api.message.MessageService.toMap(kv);
    }
}
