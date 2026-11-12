# Tectonic 宏伟地形

[English](README.en.md)

将 Tectonic 3.0.29 模组版的服务端地形功能移植到 Paper 26.3。通过“服务器 → Tectonic 宏伟地形”创建独立世界，设置高山、大陆、海洋、洞穴、地下河、气候和世界高度。

不需要 Fabric、NeoForge 或数据包。原版主世界不受影响。

## 安装

使用 Java 25 和 Paper 26.3。兼容性按原生接口检测，不限定 Paper 构建号。

1. 更新 LunarCore 核心，将 `lc-terrain.jar` 放入 `plugins/LunarCore/modules/`。
2. 将 `LC-Terrain-Agent.jar` 放在 Paper 启动文件旁。
3. 在原启动命令中加入 `-javaagent:LC-Terrain-Agent.jar`，完整重启。

```text
java -Xmx4G -javaagent:LC-Terrain-Agent.jar -jar paper.jar --nogui
```

启动补丁负责温度、客户端雪线、神殿位置和旧区块衔接，不能放进 `plugins/`。缺少补丁时面板会提示安装，不会把缺失功能显示为可用。更新补丁也需要完整重启。

## 设置

可选择经典、宏伟、极高山脉，以及官方默认、大型生物群系、荒漠、冰封荒原、极限地形，共八个预设。基础设置包含高度、横向和垂直尺度，以及丘陵、地下河、熔岩隧道、丛林石柱开关。

默认高度范围：

| 总高度 | 可用方块 Y 范围 |
| --- | --- |
| 384 | -64 至 319 |
| 512 | -64 至 447 |
| 640 | -64 至 575 |
| 704 | -64 至 639 |
| 832 | -64 至 767 |
| 1024 | -64 至 959 |

也可在面板登记自定义上下界，完整重启后选择。上下界须为 16 的倍数，最低 Y 为 -2032 至 0，上界为 256 至 2032，总高度为 256 至 4064。上界不包含在可用方块范围内。例如最低 -128、上界 896，表示总高度 1024。

高度是可生成的空间，不保证山峰达到上限。横向尺度范围为 0.5–3.0，垂直尺度范围为 0.1–15.0。

高级设置提供 34 项参数：

| 分组 | 内容 |
| --- | --- |
| 整体地形 | 积雪起点、最低高度、地形抬升、超平滑 |
| 大陆与河流 | 海洋偏移、大陆/侵蚀/山脊尺度、平坦程度、地下河照明与结冰 |
| 岛屿 | 开关、噪声尺度、倍率、偏移 |
| 海洋 | 海洋和深海深度、移除冰冻海洋结冰、海底神殿高度偏移 |
| 生物群系 | 温度与植被各自的尺度、倍率、偏移 |
| 洞穴 | 深度截断、大型洞穴、细窄洞穴、雕刻器、矿物分布 |
| 实验选项 | 替代侵蚀缩放 |

官方预设的积雪偏移为 128，极限地形为 512；神殿偏移为 -30。已有配置未填写时，积雪和神殿偏移保持 0，避免升级改变既有世界。

每个世界独立应用气候和生成设置。积雪偏移同时用于服务器温度判断和客户端雪线；移除冰冻海洋结冰保留对应群系的结构和标签；神殿高度在首次生成和读档时保持一致。熔岩底线跟随世界最低高度。

## 世界与存档

世界名称为 1–32 位小写字母、数字、下划线或横线，以字母开头。种子支持完整 64 位整数，留空随机生成。

创建后可加载、保存并卸载、设置安全出生点和传送在线玩家。有玩家的世界不能卸载；卸载不会删除存档。已创建世界的参数保持不变，避免后续区块突变。

- 登记文件：`plugins/LunarCore/modules/terrain/worlds.yml`。
- 世界目录：主世界的 `dimensions/lc_terrain/<世界名>/`。
- 生成设置：该世界的 `data/minecraft/world_gen_settings.dat`。
- `config.yml` 的 `auto-load` 控制启动时是否自动加载；手动卸载会记住该世界不自动加载。
- 不覆盖同名目录，不提供自动转换旧世界的功能。
- 已有地形世界依赖模块启动时注册的维度，不能直接移除模块后继续加载。

旧区块的混合版本、地形衔接和高度变化兼容由启动补丁处理。这不等于允许随意修改已登记世界；面板仍保留固定参数。

## 命令与权限

游戏内使用 `/tectonic debug` 查看当前位置地形信息，使用 `/tectonic locate <类型>` 寻找八类地形。控制台可追加世界名，详细用法见[命令说明](COMMANDS.zh-CN.md)。

| 权限 | 功能 |
| --- | --- |
| `terrain.view` | 面板查看状态 |
| `terrain.manage` | 面板创建、加载、卸载、设置出生点和登记高度 |
| `terrain.teleport` | 面板请求玩家传送 |
| `lc.terrain.command` | 游戏内地形信息和查找 |

游戏命令权限默认授予管理员。地形查找在后台采样，不生成区块；点击结果会建议传送命令，不会直接传送。

## 接口

前缀为 `/api/ext/terrain`：

- `GET /status`：默认参数、字段、预设、`heights`、`heightRanges` 和世界状态。
- `POST /height-ranges`：`{min-y, max-y}`，保存后需重启。
- `POST /worlds`：`{name, seed, config}`，返回 202 后查看状态。
- `POST /worlds/{name}/load`、`POST /worlds/{name}/unload`。
- `POST /worlds/{name}/spawn`：`{x, y, z}`，坐标须为整数且落点安全。
- `POST /worlds/{name}/teleport`：`{player}`。

种子传十进制字符串，避免 JavaScript 丢失精度。`config.advanced` 使用上游路径，如 `"oceans.monument_offset": -30`；缺省补默认值。出生点脚部须有两格可通行空间，下面有实体地板，无液体或危险方块。

## 来源与构建

来源固定为官方 `tectonic-3.0.29-fabric-26.3.jar`，保留 MIT 许可、作者署名和提取脚本，见 `src/main/resources/upstream/`。原版资源从 Paper 读取，不分发模组加载器或 Minecraft 资源。

地形密度、坡度、地下河装饰、矿物覆盖、雕刻器、温度、神殿偏移、旧区块兼容和命令均有对应实现。固定上游版本中无效的 `spaghetti_enabled` 和 `alternate_continents_scaling` 不显示为可用开关。

配置界面由 WebUI 提供，不包含模组客户端设置页，也不保证与模组同种子逐方块一致。大高度会增加生成和内存成本，按机器性能选择视距和高度。

```powershell
.\gradlew.bat :terrain-agent:test :lc-terrain:test :terrain-agent:jar :lc-terrain:jar "-Plc.paperServerHome=E:/LC-Test-26.3"
```

本地 Paper 参数可省略，省略时使用构建文件中的开发包。测试覆盖原生读写、世界高度、温度隔离、神殿读档、混合数据和命令采样；`tools/terrain-native-probe/` 为独立测试工具，不随插件启用。
