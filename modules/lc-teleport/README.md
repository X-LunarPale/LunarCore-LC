# 传送系统（teleport）

模块 ID：`teleport`，软依赖：`economy`（费用）、`land`（领地 TELEPORT 权限检查、rtp 避开领地）、可选 `essentials`（隐身玩家对 tpa 等视为不在线）。
行为参考 EssentialsX。

## 通用传送规则
- **预热**：`teleport.warmup` 秒（可按命令在 `warmups.*` 覆盖），期间移动出所在方块或受伤即取消；`lc.teleport.bypass.warmup` 跳过。
- **冷却**：`cooldowns.<命令>` 秒，传送成功后开始计时；`lc.teleport.bypass.cooldown` 跳过。
- **费用**：`costs.<命令>`，经 `EconomyService` 扣费（预热前检查余额、执行时扣款、传送失败自动退款）；未安装 economy 则免费；`lc.teleport.bypass.cost` 免费。tpa/tpahere 由发起者付费。
- **安全落点**：避免岩浆、火、仙人掌、岩浆块、细雪、蜘蛛网、虚空与窒息；原地不安全时同列向上/向下搜索，再在 `safe-search-radius` 内逐圈搜索。创造/旁观/飞行中不检查。管理传送（/tp 等）不做安全检查。
- **领地**：目标位于他人领地且 `LandService#can(player, loc, TELEPORT)` 为 false 时拒绝；`lc.teleport.bypass.land` 绕过。
- **Paper**：使用 `Player#teleportAsync` 与 `World#getChunkAtAsync` 异步加载区块，非 Paper 回退到 Bukkit 同步 API。
- 每次成功传送都会记录传送前位置供 `/back` 使用。

## 命令与权限

| 命令 | 说明 | 权限 | 默认 |
|---|---|---|---|
| `/tpa <玩家>`（call、tpask） | 请求传送到某玩家 | lc.teleport.tpa | 所有人 |
| `/tpahere <玩家>` | 请求对方传送到你身边 | lc.teleport.tpahere | 所有人 |
| `/tpaccept [玩家]`（tpyes） | 接受请求（不填为最近一条） | lc.teleport.tpaccept | 所有人 |
| `/tpdeny [玩家\|*]`（tpno） | 拒绝请求 | lc.teleport.tpdeny | 所有人 |
| `/tpacancel [玩家]` | 取消自己发出的请求 | lc.teleport.tpacancel | 所有人 |
| `/tptoggle` | 开关接收请求（持久化） | lc.teleport.tptoggle | 所有人 |
| `/tpaall` | 向所有人发送 tpahere | lc.teleport.tpaall | OP |
| `/tp <玩家>`、`/tp <玩家> <玩家>`、`/tp <x y z>`、`/tp <玩家> <x y z>`（支持 ~） | 管理传送（抢占原版 /tp） | lc.teleport.tp，移动他人 lc.teleport.tp.others | OP |
| `/tphere <玩家>`（s） | 把玩家拉到身边 | lc.teleport.tphere | OP |
| `/tpall [玩家]` | 所有人传送到你/某玩家 | lc.teleport.tpall | OP |
| `/tppos <x y z> [yaw pitch] [世界]` | 传送到坐标 | lc.teleport.tppos | OP |
| `/tpo <玩家> [玩家]`、`/tpohere <玩家>` | 无视 tptoggle 的强制传送 | lc.teleport.tpo（.others）、lc.teleport.tpohere | OP |
| `/top`、`/bottom` | 传送到最高方块之上 / 最低安全处 | lc.teleport.top、lc.teleport.bottom | OP |
| `/world [世界]` | 传送到世界出生点（无参数列出世界） | lc.teleport.world | OP |
| `/sethome [名\|玩家:名]` | 设置家（默认名 home） | lc.teleport.sethome，他人 lc.teleport.sethome.others | 所有人 |
| `/home [名\|bed\|玩家:名]` | 回家；不填时只有一个家或有 home 则直达，否则列出 | lc.teleport.home，他人 lc.teleport.home.others，床 lc.teleport.home.bed | 所有人 |
| `/delhome <名\|玩家:名>` | 删除家 | lc.teleport.delhome，他人 lc.teleport.delhome.others | 所有人 |
| `/homes [玩家]` | 可点击家列表 | lc.teleport.homes，他人 lc.teleport.home.others | 所有人 |
| `/setwarp <名>`、`/delwarp <名>` | 创建/移动、删除地标 | lc.teleport.setwarp、lc.teleport.delwarp | OP |
| `/warp [名] [玩家]` | 传送到地标（无参数打开 GUI） | lc.teleport.warp，送他人 lc.teleport.warp.others | 所有人 |
| `/warp seticon <名> [物品ID]` | 设置 GUI 图标（不填用手持物品） | lc.teleport.setwarp | OP |
| `/warp setdesc <名> [描述]` | 设置 GUI 描述（`\|` 换行，不填清除） | lc.teleport.setwarp | OP |
| `/warps`（listwarps） | 打开地标箱子 GUI（分页，点击传送，只显示有权限的） | lc.teleport.warps | 所有人 |
| `/warps list`、`/warp list` | 可点击文本列表 | lc.teleport.warps / lc.teleport.warp | 所有人 |
| `/setspawn` | 设置出生点（保存到 spawn.yml） | lc.teleport.setspawn | OP |
| `/spawn [玩家]` | 回出生点 | lc.teleport.spawn，他人 lc.teleport.spawn.others | 所有人 |
| `/spawn ondeath [on\|off]` | 查看、开启或关闭自己的死亡回主城 | lc.teleport.spawn | 所有人 |
| `/back`（return） | 返回上一个位置/死亡点 | lc.teleport.back；死亡点记录 lc.teleport.back.ondeath | 所有人 |
| `/rtp [世界] [玩家]`（tpr、wild、randomtp） | 随机传送 | lc.teleport.rtp，指定世界 lc.teleport.rtp.world，他人 lc.teleport.rtp.others | 所有人 |

其它权限：
- `lc.teleport.warp.<名>` / `lc.teleport.warp.*`：使用需要权限的地标（单独标记或 `warps.per-warp-permission: true`）。
- `lc.teleport.homes.<数量>`、`lc.teleport.homes.<组名>`（对应 `homes.groups`）、`lc.teleport.homes.unlimited`：家数量上限，取最大值。注意 OP 默认拥有 unlimited。
- `lc.teleport.tptoggle.bypass`：/tp、/tphere 无视对方的 tptoggle。
- `lc.teleport.bypass.warmup|cooldown|cost|land`。

## 变量
| 变量 | 说明 |
|---|---|
| `%lc_teleport_homes%` | 玩家已设置的家数量 |
| `%lc_teleport_homes_max%` | 玩家家数量上限（无限显示 ∞） |
| `%lc_teleport_warps%` | 地标总数 |

## 配置（config.yml）
- `teleport.*`：预热、移动/受伤取消、安全落点、异步、领地检查。
- `warmups.* / cooldowns.* / costs.*`：按命令（tpa、tpahere、home、sethome、warp、spawn、back、rtp）。
- `tpa.timeout`：请求超时秒数。
- `homes.default-limit / groups / bed-home / respawn-at-home`。
- `warps.per-warp-permission`；`warps.gui.enabled / rows / default-icon / filler`（地标 GUI）。
- `spawn.on-first-join / on-daily-first-join`：新玩家首次加入、每天首次上线到已设置的主城。同日再次上线保留退出位置。
- `spawn.daily-join-timezone`：每日刷新时区，留空使用服务器本地时区。
- `spawn.on-respawn / respawn-default-enabled / respawn-ignore-bed`：死亡回主城总开关、尚未选择的玩家默认设置、是否忽略床或重生锚。玩家可用 `/spawn ondeath off` 单独关闭，选择保存在玩家存档中。
- `back.on-death`；`back.persist`（/back 位置持久化到数据库，默认开启）。
- `rtp.max-attempts / avoid-biomes / avoid-lands / default-world / worlds.<世界>.{center-x,center-z,min-radius,max-radius}`（世界白名单）。

## 存储
- `lc_teleport_homes`（uuid+name 主键）、`lc_teleport_warps`（name 主键，含 icon/description 列，旧表自动补列）、`lc_teleport_toggles`（关闭 tpa 的玩家）、`lc_teleport_back`（/back 位置，`back.persist: true` 时使用）。
- 出生点保存在模块数据目录的 `spawn.yml`。
- 每日首次上线使用核心玩家档案中持久化的上次上线时间判断；在入服选择出生位置时应用主城位置，可与 QQAuth 登录限制同时使用。
- 冷却保存在内存（重启清空）。

## Web 面板（传送系统）
- 权限：`teleport.view`、`teleport.manage`。
- 概览统计；地标表（编辑“需要权限”/费用/GUI 图标/描述、删除）；玩家家表（按玩家名/家名搜索、删除）；rtp 世界参数编辑；配置表单（预热/冷却/费用/tpa 超时/家上限/rtp 参数等，保存后自动重载）。
- 所有管理操作写审计日志。
