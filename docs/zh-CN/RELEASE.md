# v0.1.0 预发行（0.1.0-pre.1）

[English](../en-US/RELEASE.md) | [首页](../../README.md)

## 本版变化

- 状态页同时显示多张趋势图，模块列表恢复版本、状态和详情。控制台显示日志匹配数量，服务器页提供管理入口。
- WebUI 用阴影标记选中项，调整了圆角和弹簧动效。
- 协管的 Web 控制台只允许执行已放行的命令。面板管理员可通过 `config.yml` 的 `commands.web-allow` 和 `commands.web-deny` 调整，禁止列表优先。
- 新增 NPC 指定世界随机传送命令，修复 `/back` 的安全落点搜索，支持按世界配置 `/rtp` 范围。
- 修复地形世界加载与 Multiverse 桥接，支持按世界限制进入下界或末地。
- 新增定时维护设置，修复聊天、TAB 和计分板的部分显示问题。

## 升级

停服后替换核心和需要更新的同版本模块，保留原有配置、数据库与世界。完整启动后，在“模块管理”检查状态。

地形仍需在 Java 命令的 `-jar` 前添加 `-javaagent:LC-Terrain-Agent.jar`。更新启动补丁后需完整重启。

## 附件

| 文件 | 用途 |
|---|---|
| `LunarCore-0.1.0-pre.1.jar` | 核心，放入 `plugins/` |
| `lc-*.jar` | 附加模块，放入 `plugins/LunarCore/modules/` |
| `LunarCore-modules-0.1.0-pre.1.zip` | 官方附加模块及结构美化管理模块 |
| `LunarCore-0.1.0-pre.1-source.zip` | 源码与构建文件 |
| `LunarCore-0.1.0-pre.1.zip` | 核心、模块、源码与安装说明 |
| `LC-Terrain-Agent.jar` | 地形启动补丁，放在服务端目录 |
| `lc-structures-web.jar` | 结构管理模块；生成插件另行下载 |
| `LcMultiverseWeb.jar`、`LcPlayerTitleWeb.jar` | 可选独立插件 |
| `module-market.json` | 模块版本、依赖与下载信息 |
| `SHA256SUMS.txt` | 下载文件校验值 |

安装步骤见[安装与升级](INSTALL.md)，模块选择见[模块使用](MODULES.md)。
