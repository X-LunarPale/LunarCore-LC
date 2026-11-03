package com.lunarboreal.api.service;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/** 玩家状态服务（由 essentials 模块提供）：隐身、AFK、上帝模式。 */
public interface EssentialsService {

    boolean isVanished(UUID player);

    boolean isAfk(UUID player);

    boolean isGod(UUID player);

    /** viewer 能否看到 target（隐身 + lbr.essentials.vanish.see 判定）。 */
    boolean canSee(CommandSender viewer, Player target);

    /** 进入 AFK 的时间戳，非 AFK 返回 0。 */
    long afkSince(UUID player);
}
