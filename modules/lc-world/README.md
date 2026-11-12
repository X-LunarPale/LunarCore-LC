# 多世界管理

目标 Paper 26.3 / Java 25，沿用项目 GPL-3.0-only。独立模块 `world`，不提供自定义生成器。

通过 Web「服务器 → 世界管理」创建、加载、安全卸载、配置和传送在线玩家。权限分别为 `world.view`、`world.manage`、`world.teleport`（Web 权限，默认不向普通账号授权）。无游戏命令、Bukkit权限或Placeholder变量。

支持 NORMAL/NETHER/THE_END 环境；普通环境允许 NORMAL/FLAT/LARGE_BIOMES/AMPLIFIED 类型，其他环境只接受 NORMAL。枚举通过 Paper26.3 API javap 核实；实际新世界生成需要实服验证，single-biome/debug和自定义生成器未开放。seed使用文本整数避免Web浮点精度丢失。

`worlds.yml` 持久记录每世界生成参数、启动加载、PvP、难度、时间、昼夜循环、天气、自动保存、`allowCrossDimension` 及可选出生点XYZ；`allowCrossDimension: false` 时，从该世界通过传送门或跨世界传送进入其他环境会被拒绝，同一环境传送不受影响；gamerules对象支持额外布尔/整数规则（API可提交，文件也可配置）。NORMAL 环境关闭 daylightCycle 并设置 time=1000 可固定白天；Paper 26.3 下界和末地没有世界时钟，时间值仍保存但不调用setTime，不承诺固定白天效果；gamerules不可覆盖PvP和昼夜专用字段。天气为应用时设置，可通过 `gamerules` 关闭 weather cycle 固定天气。模块重新启用时应用配置。配置重载请使用模块重新加载；不在运行中自动监视文件。

创建/加载接口返回 operationId（世界名），状态 `queued/running/succeeded/failed` 和错误文本保存到配置并在列表显示。世界生成在服务器主线程任务执行，可能造成生成期间卡顿。重复同名创建拒绝，加载执行中拒绝重复排队。失败记录保留，修正原因后可重试加载。启动遇损坏配置不覆盖原文件，禁用写操作并输出明确日志。

所有 Bukkit 调用通过主线程；Web路由经 ctx.sync 获取快照或执行短操作，生成另行排队。名称只允许受限ASCII，路径检查拒绝符号链接/越界；创建拒绝既有未注册目录。列表同时显示未托管的已加载世界，可使用“导入已加载世界”按钮注册实际环境、种子和当前选项；只允许已加载世界，且目录须是世界容器直接子存档根（含level.dat），或该存档下与默认维度标识/已加载世界key精确匹配的dimensions/<namespace>/<dimension>目录；不接受任意后代目录或未知目录。导入不生成世界，生成类型仅保留为元数据；其目录丢失时拒绝重新生成。导入主世界后可以配置，仍不能卸载。主世界、仍有玩家的世界以及执行中世界不能卸载；卸载先保存，绝不删除目录。模块禁用也不卸载已加载世界。

Paper 26.3 实服使用共享存档布局：默认维度位于 `world/dimensions/minecraft/overworld`、`the_nether`、`the_end`，新建命名世界可位于同一存档的 `dimensions/minecraft/<世界key>`，并没有独立的 `<世界名>/level.dat`。模块将真实存档根 `saveRoot` 和精确相对维度路径 `dimensionPath` 持久保存。卸载后重载先验证原存档的level.dat及维度目录，再调用同名WorldCreator恢复；拒绝缺失数据时重新生成。创建也检查共享存档中同名未注册维度，拒绝将未知数据作为新世界消费。旧失败记录缺少路径元数据时可能明确拒绝重试，需要管理员核实存档并恢复记录，模块不会猜测路径或删除目录。

API（均 `/api/ext/world`）：GET `/worlds`，GET `/worlds/{name}/config`；POST `/worlds` 创建，POST `/worlds/{name}/config` 部分更新，POST `/worlds/{name}/import` 导入已加载世界；POST `/worlds/{name}/load`、`/unload`、`/teleport`（body player=在线玩家名）。所有修改和传送均审计。配置创建后不允许修改seed/environment/type。

验证：`WorldOptionsTest` 覆盖目录逃逸/Windows设备名、旧目录布局、Paper 26.3默认及命名维度精确路径、错误维度/任意后代/缺失level.dat/容器外路径拒绝、整数和布尔验证、时间范围、完整long种子与固定白天往返。lead统一验证已报告本模块6项JUnit通过、全项目37项JUnit无失败；隔离实服已通过主世界导入、PvP/难度/固定白天、四种普通生成类型及安全卸载重载。下界与末地无时钟修复后的创建及重载仍待实服复验，重启后配置和失败状态持久化也需验证；测试在线拒绝卸载/主世界拒绝卸载、其他插件取消传送、无权Web拒绝、目录保留和恶意名称。传送到服务器出生点不额外搜索安全落脚，设置自定义出生点应选择安全位置。
