# lbr-ban 封禁系统（id: `ban`）

参考 LibertyBans / AdvancedBan 的思路自行实现：封禁、IP 封禁、禁言、警告阶梯、踢出、处罚历史，
并向其它模块提供 `BanService`（反作弊等模块可直接调用 `punish(...)`）。

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/ban <玩家> [原因] [-s]` | 永久封禁（原因填模板 ID 且模板带时长时自动变为临时） | `lbr.ban.ban` |
| `/tempban <玩家> <时长> [原因] [-s]` | 临时封禁 | `lbr.ban.tempban` |
| `/ipban <玩家\|IP> [原因] [-s]`（别名 ban-ip、banip） | 永久 IP 封禁（离线玩家取最后登录 IP） | `lbr.ban.ipban` |
| `/tempipban <玩家\|IP> <时长> [原因] [-s]` | 临时 IP 封禁 | `lbr.ban.tempipban` |
| `/unban <玩家> [-s]`（别名 pardon） | 解除封禁 | `lbr.ban.unban` |
| `/unbanip <IP\|玩家> [-s]`（别名 pardon-ip） | 解除 IP 封禁 | `lbr.ban.unbanip` |
| `/mute <玩家> [原因] [-s]` | 永久禁言 | `lbr.ban.mute` |
| `/tempmute <玩家> <时长> [原因] [-s]` | 临时禁言 | `lbr.ban.tempmute` |
| `/unmute <玩家> [-s]` | 解除禁言 | `lbr.ban.unmute` |
| `/warn <玩家> [原因] [-s]` | 警告（达到配置次数自动执行阶梯处罚） | `lbr.ban.warn` |
| `/unwarn <玩家> [ID\|all]` | 撤销最近一条 / 指定 / 全部警告 | `lbr.ban.unwarn` |
| `/kick <玩家> [原因] [-s]` | 踢出（会记录） | `lbr.ban.kick` |
| `/history [玩家] [页码]` | 处罚历史；查看自己默认所有人可用 | `lbr.ban.history`（他人：`lbr.ban.history.others`） |
| `/banlist [页码]` | 生效中的封禁 / IP 封禁 | `lbr.ban.banlist` |
| `/checkban <玩家\|IP>` | 查询封禁、禁言、IP 封禁、警告数 | `lbr.ban.checkban` |
| `/lbrbanreload` | 重载配置 | `lbr.ban.reload` |

- `/ban`、`/kick`、`/ipban`(ban-ip)、`/unban`(pardon)、`/unbanip`(pardon-ip)、`/banlist` 会抢占原版同名命令。
- 时长格式：`30m`、`12h`、`7d`、`1mo`、`1y`、组合 `1d12h`、`perm` 为永久。
- `-s` 静默：不全服广播，只通知拥有 `lbr.ban.notify` 的管理员（需要 `lbr.ban.silent`）。
- 原因可填模板 ID（或 `#ID`），如 `/ban Steve hack` → 原因"使用作弊客户端"，时长 30d。
- 支持离线玩家：通过 core 的 PlayerCache（曾进入过服务器的玩家）解析 UUID 与最后 IP。

## 其它权限（默认 OP）

| 权限 | 说明 |
|---|---|
| `lbr.ban.notify` | 接收静默处罚通知 |
| `lbr.ban.silent` | 使用 `-s` |
| `lbr.ban.exempt` | 免疫处罚（控制台无视）。在线玩家按权限判定；离线玩家无法查询权限插件，改为判定是否 OP；IP 封禁时若同 IP 有在线豁免玩家则拒绝。**离线的非 OP 豁免者仍可能被封禁；IP 封禁也可能连带同 IP 的离线管理员**（预登录阶段无法查权限） |
| `lbr.ban.limit.bypass` | 无视时长上限 |
| `lbr.ban.<tempban\|tempmute\|tempipban>.max.<档位>` | 时长上限档位，如 `lbr.ban.tempban.max.7d`（需开启 `duration-limits.enabled`） |

普通玩家默认权限：`lbr.ban.history`（仅能查看自己的历史）。

## 变量

| 变量 | 说明 |
|---|---|
| `%lbr_ban_muted%` | 是否禁言（true/false） |
| `%lbr_ban_banned%` | 是否被封禁 |
| `%lbr_ban_warns%` | 有效警告次数 |
| `%lbr_ban_mute_reason%` | 禁言原因 |
| `%lbr_ban_mute_remaining%` | 禁言剩余时间 |
| `%lbr_ban_mute_expires%` | 禁言到期时间 |

## 拦截

- `AsyncPlayerPreLoginEvent`：检查 UUID 封禁与 IP 封禁，踢出界面模板见语言文件 `screen.*`
  （占位符 `{reason} {operator} {date} {expires} {remaining} {id} {appeal}`）。缓存尚未加载完成时直接查库兜底。
- 禁言聊天：Paper 上监听 `AsyncChatEvent`（LOWEST 优先级取消），非 Paper 时监听 `AsyncPlayerChatEvent`。
- 禁言命令：`muted-commands` 列表中的命令（自动匹配 `插件名:命令`）。

## 配置（config.yml）

| 键 | 说明 |
|---|---|
| `server-name` | 写入记录的服务器名 |
| `appeal-url` | 申诉链接 |
| `date-format` | 日期格式 |
| `default-reason` | 默认原因 |
| `broadcast.enabled/types/api` | 广播开关、广播类型、API 处罚是否广播 |
| `reasons.<ID>.reason/duration` | 原因模板 |
| `warn.expire` | 警告有效期 |
| `warn.actions` | 警告阶梯，如 `"3": "tempmute 1h 原因"` |
| `warn.reset-after-action` | 阶梯处罚后清空警告 |
| `muted-commands` | 禁言屏蔽命令 |
| `duration-limits.*` | 时长上限权限 |
| `exempt-enabled` | 是否启用豁免权限 |
| `login-wait-millis` | 预登录等待缓存加载的最长时间 |
| `multi-server.enabled/sync-seconds` | 多服共享 MySQL：预登录缓存未命中时查库，定时增量同步其它服的新处罚与撤销（其它服撤销最多延迟一个周期） |

## 数据库

表 `lbr_ban_punishments`：`id, type(BAN/IPBAN/MUTE/WARN/KICK), uuid, name, ip, reason, operator, created, expires(NULL=永久), active, revoked_by, revoked_at, server`。
活跃的封禁 / IP 封禁 / 禁言 / 警告全部缓存在内存；所有写入（建表、插入、覆盖、撤销、过期）进入**单线程有序写队列**，
保证"封禁后立刻解封"在数据库中的顺序与内存一致；模块禁用时等待队列写完（最多 10 秒）。
ID 由内存分配（启动时以 MAX(id) 为种子），使 `BanService.punish` 可在主线程立即返回；多服共享 MySQL 主键冲突时在写线程内换号重试，
并记录旧→新 ID 映射，之后针对旧 ID 的撤销会自动改写到新 ID。

## Web 面板（权限 `ban.view` / `ban.manage`）

- 概览统计卡、生效中的处罚表（含"新增处罚"工具栏：玩家、类型、时长、原因、静默）、全部处罚记录表（撤销操作）、配置表单。
- 搜索框筛选：`ban` / `ipban` / `mute` / `warn` / `kick`、`active`，其它文字匹配玩家名/IP/原因/操作者。
- 实时推送：`ban.punish`（新处罚）、`ban.revoke`（Web 撤销）。

## BanService 使用说明（给其它模块）

- `punish(type, uuid, name, ip, reason, operator, durationMillis)`：任意线程可调用，立即返回；会自动踢出/提示在线玩家、按 `broadcast.api` 广播、写审计、推送 Web。
  `IPBAN` 未提供 ip 时自动取在线 IP 或最后登录 IP。`WARN` 会触发警告阶梯。
- `activeBan / activeIpBan / activeMute / isMuted`：读内存缓存，主线程安全。
- `history(uuid)`：**同步查库，请在异步线程调用**。
- `revoke(id, operator)`：活跃处罚立即生效；非活跃记录在异步线程时同步查库。
