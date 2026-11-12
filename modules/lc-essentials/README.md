# lc-essentials（模块 ID：essentials）基础命令

类 EssentialsX 的常用命令集合：玩家状态、物品、虚拟工作台、世界管理、礼包、物品价格。
传送（tp/home/warp/spawn/back/rtp）、聊天（msg/mail/nick/…）、封禁、金币分别由 teleport、chat、ban、economy 模块负责。

- 所有命令可在 `config.yml` 的 `disabled-commands` 中禁用；与原版同名的命令（gamemode/give/kill/time/weather/list/enchant/clear/xp）在 `override-vanilla: true` 时抢占原版，`no-override` 中列出的保留原版（默认保留 `/item`，`/i` 仍可用）。
- 权限：`lc.essentials.<命令>`，对他人执行加 `.others`。OP 默认拥有全部 `lc.*`。
- 玩家状态（god/fly/vanish/强力工具/礼包冷却）持久化在表 `lc_essentials_users`（JSON 字段），加入时恢复。

## 命令

### 玩家状态
| 命令 | 别名 | 说明 | 权限 |
|---|---|---|---|
| /gamemode <模式> [玩家] | gm、gms、gmc、gma、gmsp | 切换模式（可开启按模式权限 `lc.essentials.gamemode.<mode>`） | gamemode(.others) |
| /fly [on\|off] [玩家] | | 飞行，持久化 | fly(.others) |
| /god [on\|off] [玩家] | godmode | 无敌（免疫伤害、饥饿不下降），持久化 | god(.others) |
| /heal [玩家] | | 恢复生命/饱食/灭火/清除负面效果 | heal(.others) |
| /feed [玩家] | eat | 恢复饱食 | feed(.others) |
| /speed <0-10\|reset> [walk\|fly] [玩家] | walkspeed、flyspeed | 速度 | speed(.others) |
| /afk [玩家] | away | 切换暂离 | afk(.others) |
| /vanish [on\|off] [玩家] | v | 隐身 | vanish(.others) |
| /kill [玩家] | | 杀死 | kill(.others)，`kill.exempt` 豁免，`kill.force` 无视豁免 |
| /suicide | | 自杀 | suicide |
| /burn <玩家> [秒] | | 点燃 | burn |
| /ext [玩家] | extinguish | 灭火 | ext(.others) |
| /lightning [玩家] | smite、strike | 闪电 | lightning |
| /invsee <玩家> | inv | 查看背包（无 `invsee.modify` 时只读；`invsee.exempt` 的玩家需 `invsee.modify.exempt` 才能修改） | invsee |
| /enderchest [玩家] | ec、echest | 末影箱（他人需 `.others`，修改需 `enderchest.modify`） | enderchest(.others) |
| /clear [玩家] [all] | ci、clearinventory | 清空背包（all 含盔甲） | clear(.others) |
| /exp <give\|set\|take\|show> <数量[L]> [玩家] | xp、experience | 经验/等级 | exp(.others) |
| /ping [玩家] | latency | 延迟 | ping(.others) |
| /getpos [玩家] | coords、whereami | 坐标 | getpos(.others) |
| /depth | height | 相对海平面高度 | depth |
| /compass | direction | 朝向 | compass |
| /near [半径] | nearby | 附近玩家 | near |
| /jump | j、jumpto | 跳到看向的方块 | jump |
| /sudo <玩家> <命令\|c:聊天> | | 代执行（`sudo.exempt` 豁免，控制台无视） | sudo |
| /seen <玩家> | lastseen | 上次在线（`seen.ip` 显示 IP） | seen |
| /whois <玩家> | pinfo | 详细信息（`whois.ip` 显示 IP） | whois |
| /list | who、online | 在线列表（按 config 分组，隐藏隐身玩家，`vanish.see` 可见） | list |

### 物品
| 命令 | 别名 | 说明 | 权限 |
|---|---|---|---|
| /give <玩家> <物品> [数量] | | 给予（黑名单与上限，`give.bypass` 无视） | give |
| /item <物品> [数量] | i | 获取物品 | item |
| /more | | 补满堆叠（`more.oversized` 超堆叠） | more |
| /repair [hand\|all] | fix | 修复（all 需 `repair.all`；黑名单 `repair.bypass`；附魔物品 `repair.enchanted`） | repair |
| /enchant <附魔> [等级] | | 附魔（0 移除；超限需 `enchant.unsafe`） | enchant |
| /hat [remove] | head | 戴帽子 | hat |
| /skull [玩家名] | playerhead | 玩家头颅（他人需 `skull.others`） | skull |
| /itemname [名称] | rename、iname | 重命名（颜色需 `itemname.color`） | itemname |
| /lore <add\|set\|insert\|remove\|clear> | | 编辑描述 | lore |
| /book [author\|title] | | 解锁成书 / 签署书与笔 / 改作者（`book.author`）/ 他人的书（`book.others`） | book |
| /condense | compact、blocks | 压缩矿物为方块 | condense |
| /unbreakable | | 切换无法破坏 | unbreakable |
| /powertool <命令\|a:追加\|list\|clear\|clearall> | pt | 物品绑定命令（`c:` 前缀为聊天，`{player}` 替换为自己） | powertool |
| /worth [物品] [数量] | price | 查询价格 | worth |
| /setworth [物品] <价格> | | 设置价格（0 删除） | setworth |
| /sell <hand\|all\|物品> | | 出售（all 需 `sell.all`，需要 economy 模块） | sell |

### 虚拟工作台（无需实体方块，优先使用 MenuType 构建器）
`/workbench`(craft、wb)、`/anvil`、`/cartographytable`(cartography、ct)、`/grindstone`、`/loom`、`/smithingtable`(smithing)、`/stonecutter`、`/enchantingtable`(enchanttable、etable)、`/disposal`(trash、lcdisposal、dispose 垃圾桶)。权限同命令名。

### 世界 / 服务器
| 命令 | 别名 | 说明 | 权限 |
|---|---|---|---|
| /time [set\|add] <day\|night\|noon\|midnight\|ticks\|HH:MM> [世界\|all] | day、night、noon、midnight | 时间 | time |
| /weather <sun\|rain\|storm> [秒] [世界\|all] | sun、rain、storm、thunder | 天气 | weather |
| /ptime <reset\|值\|@值> [玩家] | playertime | 个人时间（@ 固定不流动） | ptime(.others) |
| /pweather <reset\|sun\|rain> [玩家] | playerweather | 个人天气 | pweather(.others) |
| /spawnmob <生物> [数量] [玩家] | mob、smob | 生成生物（黑名单 `spawnmob.bypass`） | spawnmob(.others) |
| /butcher [类型] [半径] [世界] | remove、killall | 清除实体：monsters/animals/ambient/items/xp/arrows/boats/minecarts/tamed/named/all/<实体类型> | butcher |
| /tree [类型]、/bigtree [类型] | largetree | 生成树 | tree |
| /gc | lag、mem、uptime | TPS/内存/世界区块实体 | gc |
| /tps | | TPS 与 MSPT | tps |

### 礼包
| 命令 | 说明 | 权限 |
|---|---|---|
| /kit [礼包] [玩家] | 无参数打开 GUI（左键领取右键预览）；给他人发放需 `kit.others`（不检查冷却） | kit；每个礼包 `lc.essentials.kits.<名称>`（或 `kits.*`，可在礼包设置 require-permission:false 免权限） |
| /kits | 列表 | kit |
| /kitpreview <礼包> | GUI 预览 | kit |
| /createkit <名称> [冷却] [once] [firstjoin] [nopermission] | 从当前背包创建，手持物品作为图标 | createkit |
| /delkit <名称> | 删除 | delkit |
`kit.bypasscooldown` 无视冷却。礼包存储于 `kits.yml`（支持 `simple-items` 手写格式）。

## 其他权限
- `lc.essentials.vanish.see` 看见隐身玩家；`vanish.onjoin` 进服自动隐身。
- `lc.essentials.afk.exempt` 不自动 AFK；`afk.kickexempt` 不被 AFK 踢出。
- `lc.essentials.signs.color` / `.format` / `.magic`：告示牌 & 颜色代码（格式码 lmno、混乱码 k）。
- `lc.essentials.anvil.color` / `.format` / `.magic`：铁砧重命名颜色代码。
- `lc.essentials.list.group.<组>`：/list 分组（在 config `list.groups` 定义）。

## 普通玩家默认权限
由 `config.yml` 的 `default-permissions` 声明（PermissionDefault.TRUE），默认：
`afk, list, ping, seen, kit, getpos, compass, depth, suicide, disposal, worth, sell, near`（均带 `lc.essentials.` 前缀）。

## 变量
| 变量 | 说明 |
|---|---|
| %lc_essentials_afk% | 是否 AFK（文本由 `placeholder.true/false` 配置） |
| %lc_essentials_afk_time% | 已 AFK 时长 |
| %lc_essentials_vanished% | 是否隐身 |
| %lc_essentials_god% | 是否上帝模式 |
| %lc_essentials_fly% | 是否允许飞行 |
| %lc_essentials_afk_count% | 全服 AFK 人数 |
| %lc_essentials_vanished_count% | 全服隐身人数 |

## 服务
提供 `EssentialsService`（isVanished / isAfk / isGod / canSee / afkSince）供 chat、teleport 等模块使用。
隐身玩家同时设置 `vanished` 元数据（兼容其它插件惯例）。

## Web 面板「基础命令」（玩家分组）
- 服务器概况统计（在线、AFK、隐身、TPS、内存、实体/区块）。
- 在线玩家状态表：AFK/隐身/上帝/飞行/模式/生命/延迟；行操作：治疗、喂食、切换飞行、切换上帝、切换隐身、切换模式。
- 礼包表：编辑显示名/冷却/一次性/首次进服/需要权限/图标，删除。
- 价格表（worth.yml）：新增/修改/删除。
- 模块配置表单。
- Web 权限：`essentials.view`、`essentials.manage`。所有管理操作写审计日志。

## 配置
见 `config.yml`（带中文注释）：AFK 自动/踢出秒数与广播、隐身行为、上帝模式、give 黑名单与上限、物品别名、修复/附魔限制、spawnmob 上限与黑名单、/near 半径、彩色告示牌/铁砧开关、礼包与价格开关、/list 分组。

## 配方
`/recipe [物品] [页码]`（别名 formula、recipes，权限 `lc.essentials.recipe`）：GUI 展示有序/无序合成、熔炉/高炉/烟熏炉/营火、切石机、锻造台配方，多配方可翻页（只读）。未指定物品时使用手持物品。

## 语言
`lang/zh_CN.yml`、`lang/en_US.yml`。

默认命令反馈不再显示“基础命令”前缀。已有服务器的自定义语言文件位于
`plugins/LunarCore/lang/<语言>/essentials.yml`，升级 JAR 不会覆盖原文件；
旧文件中的 `<prefix>` 会在发送消息时自动忽略，无需手动修改语言文件。更新 JAR 后重启服务器生效。
