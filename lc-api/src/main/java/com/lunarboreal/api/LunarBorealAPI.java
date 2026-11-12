package com.lunarboreal.api;

import com.lunarboreal.api.audit.AuditLog;
import com.lunarboreal.api.command.CommandRegistry;
import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.api.module.ModuleManager;
import com.lunarboreal.api.placeholder.PlaceholderRegistry;
import com.lunarboreal.api.player.PlayerCache;
import com.lunarboreal.api.service.ServiceRegistry;
import com.lunarboreal.api.web.WebRegistry;
import org.bukkit.plugin.Plugin;

public interface LunarBorealAPI {

    /** core 插件实例（用于调度器、事件注册的 owner）。 */
    Plugin plugin();

    String version();

    Database database();

    MessageService messages();

    CommandRegistry commands();

    ServiceRegistry services();

    WebRegistry web();

    ModuleManager modules();

    PlayerCache players();

    AuditLog audit();

    PlaceholderRegistry placeholders();

    /** 是否运行在 Paper（或其分支）上；false 表示纯 Spigot/Bukkit，模块应回退到 Bukkit API。 */
    boolean isPaper();
}
