# 领地系统（land）

模块 ID：`land`，软依赖 `economy`。参考 GriefPrevention / Residence 理念自行实现：三维 XYZ 长方体领地（旧记录迁移后保留全高度保护），
区块坐标索引（`Map<chunkKey, List<Land>>`）使所有位置查询为 O(1)~O(少量)。提供 `LandService` 服务。

## 圈地流程
1. 手持选区工具（默认金锄头 `selection-tool`），左键方块选第一点、右键选第二点；或站在位置上 `/land pos1`、`/land pos2`。
2. 选好后显示粒子边框与预计花费。
3. `/land create <名字>`：按面积 × 每格价格（可按世界配置）经 EconomyService 扣费。

校验：最小/最大边长、最小面积、单人最大领地数、单人总面积、禁止重叠、世界黑名单、与世界出生点最小距离。

## 命令（主命令 `/land`，别名 `/lands` `/ld` `/res`）
| 命令 | 说明 | 权限 |
|---|---|---|
| `/land help` | 帮助 | lbr.land.use |
| `/land pos1` / `pos2` | 以脚下位置为选区点 | lbr.land.create |
| `/land create <名字>` | 创建领地（名字全服唯一） | lbr.land.create |
| `/land delete [名字]` → `/land delete confirm` | 删除（按 `refund-ratio` 退还从服务器购买的花费） | 主人 |
| `/land info [名字]` | 领地信息（默认脚下领地） | lbr.land.use |
| `/land list [玩家]` | 领地列表（看他人需 lbr.land.list.others） | lbr.land.use |
| `/land rename [旧名] <新名>` | 重命名 | 主人 |
| `/land settp` / `/land tp <名字>` | 设置传送点 / 传送（需 TELEPORT 权限） | 管理员 / 任意 |
| `/land trust <玩家> [member\|manager]` | 添加成员/管理员（设管理员仅主人） | 管理员 |
| `/land untrust <玩家>` | 移除成员 | 管理员 |
| `/land flag <flag> <true\|false> [global\|member]` | 设置访客(全局)或成员 flag；无参数显示当前 flag | 管理员 |
| `/land expand <north\|south\|east\|west\|facing> <格数>` | 扩建（负数缩小），按增量面积收费（缩小不退款） | 主人 |
| `/land sell <价格\|cancel>` / `/land buy [名字]` | 玩家间交易，买家付款，卖家所得扣 `sale-tax`；成交后清空成员 | 主人 / 任意 |
| `/land transfer <在线玩家>` | 转让 | 主人 |
| `/land show [名字]` | 粒子显示边界（不在领地内时显示最近领地） | lbr.land.use |
| `/land message <enter\|leave> <文字\|clear>` | 自定义进出提示（MiniMessage，支持 `{land}` `{owner}` `{player}`） | 管理员 |
| `/land admin delete [名字/ID]` | 管理员删除（不退款） | lbr.land.admin |
| `/land admin tp <名字/ID>` | 管理员传送（无视 ENTER/TELEPORT） | lbr.land.admin |
| `/land admin list [玩家]` | 列出所有/某玩家领地 | lbr.land.admin |
| `/land admin bypass` | 切换绕过模式（无视保护，可管理任意领地） | lbr.land.admin |

“管理员”指领地内角色为 manager 的成员或主人。

## 权限
| 节点 | 默认 | 说明 |
|---|---|---|
| `lbr.land.use` | **所有玩家** | 使用 /land |
| `lbr.land.create` | **所有玩家** | 圈地、使用选区工具 |
| `lbr.land.list.others` | OP | 查看他人领地列表 |
| `lbr.land.admin` | OP | 管理员命令与绕过 |
| `lbr.land.limit.<n>` / `lbr.land.limit.unlimited` | - | 覆盖最大领地数（取最大值） |
| `lbr.land.area.<n>` / `lbr.land.area.unlimited` | - | 覆盖最大总面积（取最大值） |

## Flag（`LandService.Flag`）
- 玩家 flag（可分别设置访客/全局与成员值）：`BUILD` `BREAK` `INTERACT`（按钮/拉杆/压力板/其它可交互方块、村民等实体）
  `CONTAINER`（箱子等容器、展示框、盔甲架、讲台、唱片机…） `USE_DOOR`（门/活板门/栅栏门） `ANIMAL_DAMAGE` `ENTER` `TELEPORT`
- 环境 flag（仅全局）：`PVP` `MOB_SPAWN`（自然生成的敌对生物） `EXPLOSION`（苦力怕/TNT/末影水晶/床等，按 blockList 过滤） `FIRE_SPREAD`
- 主人与 manager 拥有全部玩家权限；member 使用成员 flag（成员 flag 为 false 时仍可享有访客 flag）。

保护范围：方块放置/破坏、桶、交互（门/按钮拉杆/容器分开）、可修改方块的物品（刷怪蛋、骨粉、打火石、末影水晶、船/矿车、斧锹锄去皮/压路/耕地）、
实体伤害（PVP / 动物 / 展示框·盔甲架·画·矿车·末影水晶）、爆炸、火势蔓延与燃烧、领地外液体流入、活塞跨边界推拉、怪物自然生成、
生物破坏方块（末影人、凋灵等，`protection.mob-grief`）、进入限制（ENTER）、传送进入（TELEPORT，含末影珍珠/紫颂果）。

## 变量
| 变量 | 说明 |
|---|---|
| `%lbr_land_current%` | 所在领地名（野外显示“野外”） |
| `%lbr_land_current_owner%` | 所在领地主人 |
| `%lbr_land_count%` | 拥有领地数 |
| `%lbr_land_total_area%` | 拥有领地总面积 |

## 领地税（默认关闭）
`tax.enabled: true` 后每天按面积 × `per-block-per-day` 扣费（每 10 分钟检查一次）；余额不足时冻结（冻结后主人无法传送/扩建/出售/转让/修改设置，保护仍生效）并通知，
下次扣费成功自动解冻；`delete-after-days > 0` 时冻结超过该天数自动删除。

## 存储
表：`lbr_land_lands`、`lbr_land_members`、`lbr_land_flags`。启动时异步全量加载到内存；所有修改进入单线程队列异步写库，模块禁用时等待队列写完。

## Web 面板
“领地系统”（权限 `land.view` / `land.manage`）：统计卡、领地列表（世界、坐标、面积、主人、删除/转让）、领地成员（可搜索领地名/玩家，可移除）、配置表单。

## 配置
见 `config.yml`（全部带中文注释）：选区工具、价格（按世界）、无经济时免费/禁止、退款比例、交易税、各项限制、领地税、进出提示方式、保护开关、新领地默认 flag。

## 本轮实测反馈补充（Q02/Q03/Q04/Q06）

- `economy.pricing-mode` 默认 `area`，可选 `volume`。预估、创建、水平扩建共享计价逻辑；后者保持原 Y 不变，仅收新增面积 × 有效高度的价格。税与面积限制仍按面积；旧全高记录保持全高保护，费用/绘制高度裁剪到世界实际 min/max，不把整数哨兵当实际高度。缺失世界计算使用 -64..319 兜底，实际操作应先确认世界已加载。
- 手动、创建、扩建及选区边框均使用固定 XYZ 的十二条真实边；max 坐标外沿为 max+1。单玩家每轮最多 600 粒子、XYZ 各 48 格范围；远处的真实边不会投影到玩家附近。旧全高上下边可能在视距之外，这是固定边界而非缺边。
- `border.transitions.enabled/duration-seconds/cooldown-ms` 控制成功步行和传送进出自动边框。水平、Y 和叠放切换共享每玩家冷却；双方边框最多同时两块。取消的移动/传送不触发；加入游戏不算跨界。
- `lbr.land.admin` 默认 OP；`admin.default-bypass: true` 使持有该权限的管理员默认可操作/管理玩家领地。`/land admin bypass` 可切换本次在线会话；关闭默认后需手动开启。退服重置个人开关，每次仍实时检查权限，普通玩家或显式撤销权限者不能通过旧状态绕过。环境类自然事件不使用玩家绕过。
- 海绵使用 `SpongeAbsorbEvent` 的可变 blockStates 列表逐项过滤：目标与海绵不在同一区域时保留该水方块，领地→野外、野外→领地及不同领地之间双向保护；同领地内、同为野外仍可吸水，XYZ 及叠放边界均参与判断。复用 `protection.liquid-flow`；事件缺少可靠操作者，跨界限制也适用于管理员放置的海绵，不能仅凭放置权限追溯绕过。
- `/land gui` 提供全服领地分页列表、详情、成员分页查看/确认移除、访客与成员 flag 切换、传送、购买确认、取消出售确认。`/land gui sell <价格>` 在本人领地内打开出售确认。新增成员、角色调整、命名、创建、扩建、设置传送点仍使用现有命令，GUI 不宣称完整覆盖。菜单使用文本 Component，玩家名字不解析 MiniMessage。每次菜单动作先消耗页面，再在主线程检查实时权限/归属/价格；购买复用现有命令结算及 manager，过期页面与重复点击不应重复扣款。

本轮模块自动化测试覆盖 XYZ 叠放、六方向海绵目标策略、旧全高费用裁剪、可选体积与扩建差额、管理员权限/默认/手动开关组合。Paper 客户端粒子观感、真实 SpongeAbsorbEvent 修改结果和 GUI 数据包双击尚须按 `REGRESSION.md` 人工实测；自动化不替代原报告状态。

## 已知限制
- 乘坐载具移动不触发 ENTER 限制（仅玩家移动/传送事件）。
- 领地外发射器向领地内投放会被拦截；树木/结构生长越界未拦截。
- 删除领地不退还扩建时的增量以外的交易价格（防止刷钱，退款基数仅为向服务器支付的金额）。
