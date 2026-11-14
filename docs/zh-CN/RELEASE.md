# v0.0.8 预发行（0.0.8-pre.1）

[English](../en-US/RELEASE.md) | [首页](../../README.md)

## 变化

- 聊天世界前缀可设置中文名称、颜色并预览，不改英文世界目录。聊天默认按“世界、称号、玩家名”排列。
- 聊天和 TAB 共用 PlayerTitle 当前称号，默认变量 `%playerTitle_use%`，可更换。没有佩戴称号时不显示空占位。
- 传送请求、传送结果、警告、禁言和签到增加提示音，可分别调整开关、音量、音高和间隔。
- 宏伟地形生成后自动登记到 Multiverse，已有地形世界也会补登记。MV 桥接通过地形模块加载世界，保留原有生成设置。
- 宏伟地形增加删除世界；必须输入世界名称确认。主世界、Multiverse 默认世界和有玩家的世界不能删除。
- 提供 Paper 26.3 生存服 GrimAC 预设：禁用移动模拟，放宽回弹阈值，版本敏感检测只记录和通知；保留累计违规达到阈值后的账号处罚。
- 继续使用官方 LuckPerms；核心只内置基础命令，附加模块按需安装。

## 附件

| 文件 | 用途 |
|---|---|
| `LunarCore-0.0.8-pre.1.jar` | 核心，放入 `plugins/` |
| `lc-*.jar` | 附加模块，放入 `plugins/LunarCore/modules/` |
| `LunarCore-modules-0.0.8-pre.1.zip` | 官方附加模块及结构美化管理模块 |
| `LunarCore-0.0.8-pre.1-source.zip` | 源码与构建文件 |
| `LunarCore-0.0.8-pre.1.zip` | 核心、模块、源码与安装说明 |
| `LC-Terrain-Agent.jar` | 地形启动补丁，放在服务端目录并添加启动参数 |
| `lc-structures-web.jar` | 结构管理模块；生成插件另行下载 |
| `LcMultiverseWeb.jar`、`LcPlayerTitleWeb.jar` | 可选独立插件 |
| `module-market.json` | 版本、依赖与文件校验信息 |
| `SHA256SUMS.txt` | 下载文件校验值 |

地形启动参数为 `-javaagent:LC-Terrain-Agent.jar`，放在 Java 命令的 `-jar` 前。更新启动补丁后需完整重启。

升级步骤见[安装](INSTALL.md)。测试记录见[验证记录](VALIDATION.md)，结构生成范围见[结构美化](STRUCTURES.md)。

聊天和 TAB 由 LC 统一管理时，请关闭 PlayerTitle 自带的 `isChat`、`isTabPrefix` 和 `isTabSuffix`，避免重复显示。世界名称、颜色和称号设置位于后台“聊天与社交”。

GrimAC 预设位于 `modules/lc-anticheat/src/main/resources/presets/paper-26.3.yml`。它是配置合并内容，不能直接替换整个 GrimAC 配置文件；需要合并主配置与处罚分组，并设置 LuckPerms 默认组的 `grim.exempt.simulation=true`。
