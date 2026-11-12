# 经济系统（economy）

金币账户、转账、排行榜、流水账、Vault 桥接。提供 `EconomyService` 服务供其它模块（领地、签到…）使用。

## 实现要点
- 金额全部使用 `BigDecimal`，小数位可配（向下截断）；数据库中以字符串存储，避免精度丢失。
- 所有账户常驻内存（`ConcurrentHashMap`），读写即时；脏账户与流水由异步定时任务批量写库（`storage.flush-interval`），模块禁用/热重载时同步写回。
- 表：`lc_economy_accounts`（uuid, name, balance, created, updated）、`lc_economy_transactions`（id, time, uuid, name, amount, balance, reason, operator）。
- 安装了 Vault 时自动以 **High** 优先级注册 `net.milkbowl.vault.economy.Economy`（不支持银行，世界参数忽略），模块禁用时注销。桥接类位于 `com.lunarboreal.economy.vault`，仅在 Vault 类可见时才加载。

## 命令
| 命令 | 别名 | 说明 | 权限 |
|---|---|---|---|
| `/money [玩家]` | `/bal` `/balance` | 查看自己/他人余额 | `lc.economy.balance`（他人：`lc.economy.balance.others`） |
| `/money log [玩家] [页]` | | 查看流水 | `lc.economy.log`（他人：`lc.economy.log.others`） |
| `/pay <玩家> <金额>` | | 转账（支持 1k / 1w / 1m 后缀；超过阈值需 `/pay confirm`） | `lc.economy.pay` |
| `/baltop [页]` | `/moneytop` `/balancetop` | 财富排行 | `lc.economy.baltop` |
| `/eco give\|take\|set\|reset <玩家\|*> [金额]` | `/economy` | 管理余额（`*` = 所有在线玩家），写审计日志 | `lc.economy.admin` |

**普通玩家默认权限**：`lc.economy.balance`、`lc.economy.pay`、`lc.economy.baltop`、`lc.economy.log`。其余默认仅 OP。

## 变量
| 变量 | 说明 |
|---|---|
| `%lc_economy_balance%` | 余额（纯数字） |
| `%lc_economy_balance_formatted%` | 格式化余额（含货币名） |
| `%lc_economy_balance_short%` | 带千分位的数字 |
| `%lc_economy_rank%` | 财富排名（无则 `-`） |
| `%lc_economy_top_<n>_name%` | 第 n 名名字 |
| `%lc_economy_top_<n>_balance%` / `_balance_formatted` | 第 n 名余额 |
| `%lc_economy_currency%` / `total` / `accounts` | 货币名 / 全服总额 / 账户数 |

## 配置（config.yml）
- `currency.*`：货币名（单/复数）、小数位、显示格式、千分位
- `account.initial-balance` / `max-balance`：初始余额 / 最大余额（<=0 不限）
- `storage.flush-interval` / `top-refresh`：刷盘与排行刷新间隔（秒，需重载模块生效）
- `pay.*`：最小金额、手续费百分比、最低手续费、确认阈值与有效期
- `baltop.page-size`、`log.page-size`
- `vault.enabled` / `vault.name`

## EconomyService 错误码（Result.error）
`invalid_amount`、`insufficient_funds`、`max_balance`、`no_account`、`same_account`、`loading`（模块刚启用、账户尚未加载完成）。
`deposit/withdraw/set/transfer` 遇到无账户的 UUID 会自动以初始余额开户；`balance()` 不会开户（无账户返回 0）。
加载完成前：查询返回 0/false，修改返回 `loading`，绝不在内存中创建账户（防止覆盖数据库余额）。
取整：扣款金额按小数位**向上**取整、入账/设置向下截断；非零金额取整后为 0 时返回 `invalid_amount`。
Vault 按名字调用时只使用本地缓存解析玩家（不发网络请求），未知名字返回失败且不建账户。
`currency.format` 请使用纯文本（金额作为消息占位符插入时会被转义）。

## Web 面板（经济系统）
- 权限：`economy.view`、`economy.manage`
- 统计卡：货币总量、账户数、今日流水笔数、今日流入总额
- 账户表：搜索、排序、增加/扣除/设置/重置（写审计 + 流水，操作者 `web:<用户>`）、工具栏给任意玩家加钱
- 流水表：按玩家名/原因/操作者搜索
- 配置表单：保存后写 config.yml 并重载
- 路由：`GET /api/ext/economy/stats|accounts|transactions|config`，`POST accounts/{uuid}/give|take|set|reset`、`accounts/create`、`config`
