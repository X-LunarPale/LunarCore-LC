package com.lunarboreal.api.web;

import java.util.function.Consumer;

/**
 * Web 面板扩展点。任何模块或外部 Bukkit 插件都可以：
 * <ol>
 *   <li>注册 REST 路由：挂载在 /api/ext/{namespace}/... ，需要登录，可按权限节点控制；</li>
 *   <li>注册声明式面板 {@link WebPanel}：前端自动渲染表格/表单/统计卡/iframe，无需写前端代码；</li>
 *   <li>注册静态资源：jar 内某目录映射到 /ext/{namespace}/...，用于完全自定义的页面（配合 iframe 面板）。</li>
 * </ol>
 * owner 为 LbrModule 或 Plugin，卸载时 {@link #unregisterAll(Object)} 自动清理。
 */
public interface WebRegistry {

    void routes(String namespace, Object owner, Consumer<WebRouter> builder);

    void registerPanel(String namespace, WebPanel panel, Object owner);

    void registerStatic(String namespace, ClassLoader loader, String resourceRoot, Object owner);

    /** 声明一个 web 权限节点，供角色管理界面勾选。 */
    void registerPermission(String node, String description, Object owner);

    void unregisterAll(Object owner);

    /**
     * 向所有已连接面板推送实时事件（WebSocket /api/ws），topic 例如 "console"、"chat"、"anticheat.alert"。
     * <p>
     * 订阅权限按 topic 前缀映射：取第一段（按 "." 分割）+ ".view" 作为所需权限节点，
     * 如 ban.punish → ban.view、antixray.alert → antixray.view、chat → chat.view；
     * 特例 console → core.console.view、dashboard → core.dashboard。
     * 若该节点没有通过 {@link #registerPermission} 声明，则仅 Web 管理员（"*"）可订阅。
     * 推送敏感信息（IP、聊天内容等）的模块请务必声明对应的 "&lt;前缀&gt;.view" 权限。
     * <p>
     * 前端协议：连接 /api/ws?token=JWT，发送 {"op":"sub","topic":"x"} 订阅，收到 {"topic":"x","data":payload}。
     */
    void broadcast(String topic, Object payload);

    boolean isRunning();

    int port();
}
