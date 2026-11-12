# v0.0.7

[English](../en-US/RELEASE.md) | [首页](../../README.md)

## 变化

- 补齐 Tectonic 服务端地形功能：按世界设置气候、雪线、神殿高度、新旧区块衔接和自定义高度，增加地形信息与八类定位命令。需安装启动补丁并完整重启。
- 玩家可用 `/spawn ondeath off` 关闭死亡回主城，`/spawn ondeath on` 恢复；回城后提供可点击提示，选择会保存。
- 每天首次上线回到已设置的主城；同日重连保留退出位置，日期可按配置时区计算。
- 修正 QQAuth 配套 AstrBot 插件的默认端口，支持 OneBot/NapCat 群消息，并补充连接错误说明。
- 新增结构美化管理模块，提供全服及按世界设置。生成插件 `LunarCoreStructures.jar` 需另行安装，不包含在核心中。
- 核心仍只内置基础命令，19 个官方附加模块按需安装；保留已有配置、数据库和世界。

## 附件

| 文件 | 用途 |
|---|---|
| `LunarCore-0.0.7.jar` | 核心，放入 `plugins/` |
| `lc-*.jar` | 附加模块，放入 `plugins/LunarCore/modules/` |
| `LunarCore-modules-0.0.7.zip` | 官方附加模块及结构美化管理模块 |
| `LunarCore-0.0.7-source.zip` | 源码与构建文件 |
| `LunarCore-0.0.7.zip` | 核心、模块、源码与安装说明 |
| `LC-Terrain-Agent.jar` | 地形启动补丁，放在服务端目录并添加启动参数 |
| `lc-structures-web.jar` | 结构管理模块；生成插件另行下载 |
| `LcMultiverseWeb.jar`、`LcPlayerTitleWeb.jar` | 可选独立插件 |
| `module-market.json` | 版本、依赖与文件校验信息 |
| `SHA256SUMS.txt` | 下载文件校验值 |

地形启动参数为 `-javaagent:LC-Terrain-Agent.jar`，放在 Java 命令的 `-jar` 前。更新启动补丁后需完整重启。

升级步骤见[安装](INSTALL.md)。测试记录见[验证记录](VALIDATION.md)，结构生成范围见[结构美化](STRUCTURES.md)。
