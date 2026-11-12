# LuckPerms 权限设置

[English](../en-US/PERMISSIONS.md) | [首页](../../README.md)

LC 使用官方 LuckPerms 管理游戏权限。网页中的“权限管理”直接读取和修改 LP 数据，不再单独计算权限。面板账号与角色仍在“账号与角色”管理。

## 安装

1. 从 [LuckPerms 官网](https://luckperms.net/download) 下载 Bukkit 版，放入服务器的 `plugins/`。
2. 在 LC 模块市场安装“LuckPerms 权限桥接”；手动安装时，将 `lc-permissions.jar` 放入 `plugins/LunarCore/modules/`。
3. 完整重启服务器，在 LC 面板打开“权限管理”。

Windows 也可在项目目录执行：

```powershell
.\tools\install-luckperms.ps1 -ServerDirectory "D:\Minecraft"
```

脚本从官方接口获取最新版，只下载并放置插件，替换同目录的旧官方 Bukkit 版；不会启动或重启服务器。LuckPerms 属于独立插件，不要放进 LC 模块目录。

未安装或尚未启用 LP 时，面板显示连接状态，不能修改游戏权限。

## 使用

- 在“权限组”创建组、设置权重、继承关系与权限。
- 在“玩家”设置所属组、允许或拒绝节点；可设置临时有效期。
- 在“已注册权限节点”查找 LC 和其他插件的节点。
- 使用权限检查查看指定玩家的实际权限。

这些操作与 `/lp` 使用同一份数据。无需重新创建 LP 中已有的组和玩家。

LC 命令节点以 `lc.` 开头。例如，将玩家加入组并允许飞行：

```text
/lp user 玩家名 parent add member
/lp group member permission set lc.essentials.fly true
```

先用 `/lp creategroup member` 创建尚不存在的组。其他节点以面板列表为准，`true` 表示允许，`false` 表示拒绝。

## 旧数据

首次连接 LP 时，桥接模块会读取原有 `plugins/LunarCore/modules/permissions/permissions.json`，迁入权限组、玩家、继承和仍有效的临时授权。旧节点会改为 `lc.*`。成功后记录迁移状态，原文件保留，之后以 LP 数据为准。

不要再次把旧自实现权限模块装回服务器。后续直接在面板或 `/lp` 修改权限。
