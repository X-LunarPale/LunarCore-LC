package com.lunarboreal.api.command;

import com.lunarboreal.api.module.LcModule;
import org.bukkit.plugin.Plugin;

public interface CommandRegistry {

    /** 由模块注册，模块禁用时自动注销。 */
    void register(LcModule owner, LcCommand command);

    /** 由外部 Bukkit 插件注册，需自行注销或在插件卸载时自动注销。 */
    void register(Plugin owner, LcCommand command);

    void unregister(LcCommand command);

    /** 配置 commands.disabled 列表里的命令不会被注册。 */
    boolean isDisabled(String name);
}
