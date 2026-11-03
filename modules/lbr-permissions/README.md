# 权限管理

独立内置模块 `permissions`，Paper 26.3 / Java 25，GPL-3.0-only。使用公开 Bukkit PermissionAttachment，不修改 OP、不反射替换 Permissible，不冒充 LuckPerms API。

## 管理入口

后台「管理 → 权限管理」提供用户组、玩家直接记录、已注册节点、有效权限和指定节点检查。Web 权限为 `permissions.view` 与 `permissions.manage`，与游戏权限分开；Web 管理员仍由现有后台账号体系管理。

游戏管理员节点 `lbr.permissions.admin` 默认 OP，控制台可用 `/lperm`（别名 `/lperms`、`/permissions`）。不注册 `/lp`。

```text
/lperm group vip create
/lperm group vip weight 10
/lperm group vip parent add default
/lperm group vip permission set lbr.teleport.home true
/lperm user 玩家名 parent add vip
/lperm user 玩家名 parent add vip 7d
/lperm user 玩家名 permission set some.plugin.node false
/lperm user 玩家名 permission set some.plugin.node true 30min
/lperm user 玩家名 permission unset some.plugin.node
/lperm user 玩家名 parent remove vip
/lperm user 玩家名 check some.plugin.node
/lperm list groups
/lperm list nodes
/lperm reload
```

玩家可用完整 UUID 或曾加入的已知名称；未知名称不会触发联网查询。有效期支持秒、s、m/min、h、d，0 或留空表示永久。临时到期按刷新周期应用，默认20tick，服务器卡顿时以实际tick执行为准。

## 规则

默认 `default` 组始终参与解析。优先级依次为：玩家直接记录优于组记录；同来源精确节点优于最长后缀通配符，再优于 `*`；组权重高优先；同权重继承层数浅优先；最终同级拒绝优先。单个组与玩家节点各只能保存一个值/到期时间。默认组不能删除，仍被继承或被用户引用的组不能删除；继承循环拒绝保存。

通配符只支持 `*` 和末尾 `.*`，会展开到插件已注册的节点、子节点及已配置的具体节点。完全未注册、也从未配置的任意字符串不可能由普通 Bukkit 附件完整拦截；这不是 LuckPerms 全功能替代。未配置节点保留 Bukkit 默认权限规则。Bukkit 父权限的 children 传播仍遵循 Bukkit 语义；模块按完整子节点图拓扑顺序应用，确保配置的明确子节点结果不会被父节点覆盖。

模块仅移除自己的附件，停用后恢复 Bukkit 默认及其他插件附件。检测到已启用 LuckPerms/PermissionsEx/GroupManager/UltraPermissions 时默认拒绝启用，避免多提供者冲突。无需同时安装 LuckPerms。

## 数据与验证

数据保存在模块目录的 `permissions.json`，不可变快照，临时文件原子替换后才发布；损坏文件拒绝覆盖。可关闭模块后备份，或使用控制台重载修复后的文件。Web 修改记录审计。

测试覆盖继承、通配符、拒绝优先、到期、循环、持久化失败及 Bukkit 子节点拓扑顺序。测试服 API 验证使用离线临时 UUID，实际在线玩家 `hasPermission` 和第三方插件权限行为仍需在线验收。PlayerTitle 的已注册权限可在节点表中查询；称号本身仍由 PlayerTitle 管理页面创建、授予和佩戴。
