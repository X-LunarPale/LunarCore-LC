package com.lunarboreal.api.message;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多语言消息服务。
 * <ul>
 *   <li>语言文件：模块 jar 内 lang/zh_CN.yml、lang/en_US.yml，core 释放到 plugins/LunarCore/lang/&lt;locale&gt;/&lt;moduleId&gt;.yml 供服主修改。</li>
 *   <li>key 全称为 "&lt;moduleId&gt;.&lt;path&gt;"，例如 economy.balance.self。core 自身命名空间为 "core"。</li>
 *   <li>文本格式为 MiniMessage，支持 &lt;prefix&gt; 标签：每个命名空间使用自身语言文件的 prefix，缺省回退为 [命名空间]；{name} 为占位符。</li>
 *   <li>若值为列表则按多行发送。</li>
 * </ul>
 */
public interface MessageService {

    /** 注册模块语言包（core 在加载模块时自动调用，外部插件可手动调用）。 */
    void registerBundle(String namespace, ClassLoader loader);

    void unregisterBundle(String namespace);

    void send(CommandSender to, String key, Object... placeholders);

    Component component(String key, Object... placeholders);

    /** 返回命名空间配置的消息前缀（已替换 legacy/MiniMessage 格式，未解析）。 */
    default String prefix(String namespace) { return ""; }

    /** 返回替换占位符后的原始 MiniMessage 字符串（未解析）。 */
    String raw(String key, Object... placeholders);

    /** 直接解析一段 MiniMessage（会处理 &lt;prefix&gt; 和 & 颜色码）。 */
    Component parse(String miniMessage, Object... placeholders);

    String locale();

    /**
     * 占位符值的安全规则：普通 String 值会被转义（MiniMessage.escapeTags），防止玩家输入注入 click/hover 等标签；
     * Component 值原样插入；需要把一段可信的 MiniMessage 文本作为富文本插入时，用 {@link #rich(String)} 包装。
     */
    record Rich(String miniMessage) {
        @Override public String toString() { return miniMessage; }
    }

    /** 标记一段可信 MiniMessage 文本（如服主配置的奖励描述），作为占位符值时不转义。 */
    static Rich rich(String miniMessage) {
        return new Rich(miniMessage == null ? "" : miniMessage);
    }

    static Map<String, Object> toMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
