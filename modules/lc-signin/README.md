# 每日签到（signin）

每日签到、连续/累计签到奖励、补签（消耗金币）、箱子日历界面、登录提醒。软依赖 `economy`（金币奖励与补签花费通过 `EconomyService`）。

## 实现要点
- 日期按配置时区（默认 `Asia/Shanghai`）计算，每天 0 点自动刷新；每 30 秒检测跨天并提醒在线未签到玩家（可关）。
- 表：`lc_signin_records`（id, uuid, name, date, time, makeup，`(uuid,date)` 唯一索引）、`lc_signin_players`（uuid, name, streak, total, last_date, updated，用于排行）。
- 在线玩家的全部签到日期异步加载到内存，连续天数 = 以今天（已签）或昨天为终点向前连续的天数，累计 = 签到天数；签到时内存即时更新，数据库异步写入。
- 补签只能补本月今天之前的日期，受每月次数上限限制，花费由 `EconomyService.withdraw` 扣除（失败不补签）。

## 命令
| 命令 | 说明 | 权限 |
|---|---|---|
| `/signin`（别名 `/qd` `/checkin`） | 打开本月签到日历（`gui-enabled: false` 时直接签到） | `lc.signin.use` |
| `/signin sign` | 直接签到 | `lc.signin.use` |
| （界面点击过去未签日期） | 补签 | `lc.signin.makeup` |
| `/signin top [streak\|total] [页]` | 连续/累计排行 | `lc.signin.top` |
| `/signin admin reset <玩家>` | 清空玩家签到数据（写审计） | `lc.signin.admin` |

**普通玩家默认权限**：`lc.signin.use`、`lc.signin.makeup`、`lc.signin.top`；`lc.signin.admin` 默认仅 OP。

## 日历界面
6 行箱子：第 1 列为「我的签到」信息 / 签到按钮 / 奖励一览，第 2~8 列为周一~周日，第 9 列右上角为关闭。
物品状态：已签、已补签、今天（点击签到）、可补签（点击补签，显示花费）、不可补签、未来日期；材质在 `config.yml` 的 `gui.*` 中可改，物品数量 = 日期。

## 变量
| 变量 | 说明 |
|---|---|
| `%lc_signin_today%` | 今日是否已签（文本见 `placeholder.signed/unsigned`） |
| `%lc_signin_today_bool%` | true / false |
| `%lc_signin_streak%` | 当前连续天数 |
| `%lc_signin_total%` | 累计签到天数 |
| `%lc_signin_month%` | 本月签到天数 |
| `%lc_signin_makeup_left%` | 本月剩余补签次数 |

（变量基于在线玩家缓存，离线玩家返回 0。）

## 配置
`config.yml`：`timezone`、`gui-enabled`、`broadcast`、`reminder.*`、`makeup.*`（enabled / cost / monthly-limit / give-daily-reward / check-milestones）、`placeholder.*`、`gui.*`（材质）。

`rewards.yml`（单独文件，删除的条目不会被默认值补回）：
```yaml
rewards:
  daily: { description: "...", money: 100, exp: 10, items: [], commands: [] }
  streak:          # 连续第 N 天额外奖励
    "7": { money: 1000, items: [ { material: DIAMOND, amount: 2, name: "<aqua>钻石" } ] }
  streak-cycle: 7  # >0 时按周期循环（第 8 天视为第 1 天）
  milestones:      # 累计第 N 天一次性奖励
    "30": { money: 5000, commands: [ "say {player} 累计签到 30 天" ] }
```
奖励类型：`money`、`exp`、`items`（material / amount / name / lore，MiniMessage）、`commands`（控制台执行，`{player}` `{uuid}`）、`description`（展示用）。

## Web 面板（每日签到）
- 权限：`signin.view`、`signin.manage`、`signin.rewards`（编辑奖励；奖励 commands 以控制台执行，**等同控制台权限**，只授予可信管理员）
- 统计：今日签到人数、本月补签次数、本月签到人次、连续签到中玩家、签到过的玩家
- 排行榜表（`sort=streak|total`，行操作「重置」）、签到记录表（按名字/UUID/日期前缀搜索）
- 配置表单（`signin.manage`）；奖励表单（`signin.rewards`，code 编辑器编辑 rewards.yml，保存前校验 YAML）

## 数据一致性
签到写库走异步队列；尚未落库的签到记在“写入中”表里（不随玩家退出清除），玩家重新加载数据时合并，防止“签到→秒退→秒进”重复领奖；写库失败时保留该标记并记录 SEVERE 日志；模块禁用时同步写回队列。
