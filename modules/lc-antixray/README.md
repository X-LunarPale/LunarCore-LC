# lc-antixray 反矿透（id: `antixray`）

三套方案可组合使用：

| 方案 | 说明 | 默认 |
|---|---|---|
| 一：Paper 内置 anti-xray（混淆） | 面板 / 命令查看、修改 `config/paper-world-defaults.yml` 与 `<世界>/paper-world.yml` 中的 `anticheat.anti-xray`，内置推荐预设。**修改后需重启服务器**。非 Paper 时提示不可用 | 由服主决定 |
| 二：自研轻量假矿 | 对玩家把未暴露（六面均不透明）的矿石 `sendBlockChange` 伪装成石头 / 深板岩 / 下界岩，暴露时还原 | 关闭 |
| 三：挖矿行为检测 | 比率、密集、直线命中检测 → 通知管理员、Web 推送、写库、可选自动命令 | 开启 |

## 命令（`/antixray`，别名 `/ax`、`/xray`，权限 `lc.antixray.admin`）

| 子命令 | 说明 |
|---|---|
| `stats <玩家>` | 累计挖矿统计（普通方块 / 各稀有矿），在线时附带检测窗口实时数据 |
| `alerts [玩家] [页]` | 告警记录 |
| `top` | 可疑玩家排行前 10 |
| `paper` | 列出全局默认与各世界的 anti-xray 概要及可用预设 |
| `paper <世界\|defaults> [show]` | 查看详细设置（标注继承默认的键） |
| `paper <世界\|defaults> <预设>` | 应用预设（需 `lc.antixray.paper`） |
| `paper <世界\|defaults> set <键> <值>` | 修改单个键：enabled / engine-mode / max-block-height / update-radius / lava-obscures / use-permission；值为 `default` 时删除覆盖 |
| `fakeore` | 假矿方案状态（缓存区块数、伪装中的矿石数） |
| `reload` | 重载配置 |

预设：`overworld-1`、`overworld-2`（推荐）、`overworld-3`、`nether-2`、`nether-3`、`disable`。

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `lc.antixray.admin` | OP | 使用 /antixray |
| `lc.antixray.paper` | OP | 修改 Paper anti-xray 配置 |
| `lc.antixray.notify` | OP | 接收告警 |
| `lc.antixray.bypass` | **false** | 不被检测、不被假矿伪装（默认 OP 也没有，便于管理员测试） |

## 变量

| 变量 | 说明 |
|---|---|
| `%lc_antixray_alerts%` | 本次在线告警次数 |
| `%lc_antixray_veins%` | 本次在线挖到的稀有矿脉数 |
| `%lc_antixray_mined_<材料>%` | 本次在线挖到某材料数量，如 `mined_diamond_ore`；普通方块合计为 `mined_common` |

## 方案二：假矿实现与局限

- Paper：监听 `PlayerChunkLoadEvent`（区块发给客户端时）立即排队；非 Paper：每 10 tick 增量扫描视距内新进入的区块，远离视距的区块标记丢弃以便再次进入时重新伪装。
- 主线程按预算（`chunks-per-tick`）获取中心 + 四邻区块的 `ChunkSnapshot`，异步线程计算未暴露矿石并缓存；主线程按每玩家 `packets-per-tick` 限频发包。
- 暴露：方块破坏、方块/实体爆炸、活塞、燃烧、液体流动、实体改变方块（末影人、沙子/沙砾下落）、方块褪变（冰融化等）时检查相邻位置，下一 tick 向 `reveal-distance` 内玩家发送真实方块；区块卸载时释放缓存。
- 一致性：每个在途计算的区块有版本号，计算期间区块内发生暴露或区块卸载会使版本递增，异步结果回主线程比对版本后才写入缓存（否则丢弃并按需重算），避免"幽灵石头"与卸载区块缓存泄漏。
- **局限**：区块原始数据先于伪装包到达客户端（窗口通常 1~数 tick），收包即缓存区块的作弊客户端无法防御；每个方块一个包，矿多时流量明显；放置方块使矿石重新被包围不会再次伪装；邻区块未加载时边界矿石保守视为暴露不伪装。Paper 服务器请优先使用方案一。

## 方案三：检测规则

以"矿脉"为单位（2 分钟内与已挖同种矿石相邻的方块算同一矿脉）：
- **密集 DENSE**：窗口内稀有矿脉加权和 ≥ `dense.threshold`。
- **比率 RATIO**：窗口内矿脉数 ≥ `ratio.min-veins` 且 普通方块数/矿脉数 < `ratio.max-stone-per-vein`。
- **直线命中 DIRECT**：矿脉被挖前完全未暴露（周围非实心方块都是该玩家 5 分钟内自己挖开的），且最近 `lookback-blocks` 个挖掘点中 ≥ `approach-ratio` 比例持续逼近矿脉、起点距离 ≥ `min-start-distance`；窗口内次数 ≥ `direct.threshold` 告警。
- 同类告警冷却 `alert-cooldown-seconds`；创造/旁观模式与 `lc.antixray.bypass` 不检测；玩家放置后再挖掉的稀有矿不计入（内存记录，重启后清空）。
- 告警：通知 `lc.antixray.notify`、控制台、Web 推送 `antixray.alert`、写表、可选 `actions.commands`（控制台执行，可配合 ban 模块，如 `warn {player} ... -s`）。

## 配置要点（config.yml）

- `fake-ore.*`：enabled、worlds、max-y、radius-chunks、chunks-per-tick、packets-per-tick、reveal-distance、ores。
- `detection.*`：window-minutes、max-y、rare-ores（材料: 权重）、common-blocks、dense / ratio / direct、alert-cooldown-seconds。
- `actions.*`：enabled、types、min-alerts、commands。
- 注意：core 会把 jar 内默认值补全到配置文件，删除 `rare-ores` 中的默认项会被补回，如需忽略某矿请把权重设为 0。

## 数据库

- `lc_antixray_mined(uuid, name, material, amount)`：累计挖掘数量（稀有矿按材料，普通方块合计为 `COMMON`），内存累积、每 5 分钟 / 玩家退出 / 模块禁用时增量写回。
- `lc_antixray_alerts(id, uuid, name, type, detail, world, x, y, z, created)`。

## Web 面板（权限 `antixray.view` / `antixray.manage`）

概览统计、可疑玩家排行（可疑分 = 告警数×20 + min(每千石头稀有矿加权数, 500)/5）、告警记录、
Paper anti-xray 设置表（行操作：应用预设 / 修改引擎、高度等）、配置表单。
