# LuckPerms 权限桥接

[English](README.en.md)

将官方 LuckPerms 的玩家、权限组、继承、权重和临时授权接入 LC 网页。权限计算与存储由 LuckPerms 负责。

1. 将官方 LuckPerms Bukkit 版放入 `plugins/`。
2. 将 `lc-permissions.jar` 放入 `plugins/LunarCore/modules/`。
3. 完整重启服务器，打开面板中的“权限管理”。

网页查看权限为 `permissions.view`，修改权限为 `permissions.manage`。这两个权限属于面板账号，与游戏权限分开。

游戏内使用官方 `/lp` 命令，LC 节点以 `lc.` 开头。首次连接时自动导入原 `permissions.json`，保留拒绝项与未到期临时授权；成功后以 LP 数据为准。

完整说明见 [权限设置](../../docs/zh-CN/PERMISSIONS.md)。
