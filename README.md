# LunarCore 琉月核心

**预览测试版** Paper 26.3 build 202 · Java 25 · GPL v3
**注意：本项目绝大多数由GPT-6.1-Sol、GPT-6-Astra、Claude-Opus-5.5、Deepseek-V4.1-Flash等AI及其Agent创作！绝大多数已通过人工测试！**
若出现问题，请**提交issue**！

[English](README.en.md) | [下载 v0.0.6](https://github.com/X-LunarPale/LunarCore-LC/releases/tag/v0.0.6-preview) | [安装与升级](docs/zh-CN/INSTALL.md) | [GitHub 模块市场](module-market/README.md) | [参与开发](CONTRIBUTING.md)

LunarCore 琉月 为 Minecraft 生存服务器提供基础命令和网页管理面板。经济、领地、聊天与 AI 等功能可以单独安装。

## 安装

1. 下载 `LunarCore-0.0.6-preview.jar`，放入服务端的 `plugins/`。
2. 启动服务器，等待依赖下载完成。
3. 在服务器所在电脑打开 `http://127.0.0.1:8765`，按控制台提示登录并修改密码。
4. 在“模块管理 → 模块市场”安装需要的功能，下载完成后完整重启。

核心只包含基础命令模块，以及面板、账号、数据库和操作记录。附加模块 JAR 放在 `plugins/LunarCore/modules/`，不能直接放入 `plugins/`。

旧版升级会保留已有模块、配置、数据库和世界。先停服并备份，步骤见[安装与升级](docs/zh-CN/INSTALL.md)。

## 功能

| 范围 | 功能 |
|---|---|
| 核心 | 网页管理、玩家管理、控制台、账号与角色、可选两步验证 |
| 基础命令 | 飞行、治疗、物品、工作台、时间、天气与礼包 |
| 生存 | 经济、签到、领地、玩法投票、探索手册、建筑地标 |
| 玩家 | 传送、聊天与多 AI、称号、计分板 |
| 保护 | 处罚、反矿透、GrimAC 配置、游戏权限、QQ 认证 |
| 服务器 | 世界管理、宏伟地形、性能、服务器列表文字 |

## 模块市场

[GitHub 模块市场](module-market/README.md) 提供模块说明和独立下载。面板支持安装、更新、卸载与取消变更；每次处理一组变更，需要的依赖会一起下载。显示“等待重启”后，完整重启即可应用。卸载保留模块配置和数据。

公开仓库与模块下载无需令牌。所有人均可[提交社区模块](CONTRIBUTING.md)，也可通过 Issues 填表投稿。详见[模块使用](docs/zh-CN/MODULES.md)。

## 使用文档

- [面板、AI 与 QQ 认证](docs/zh-CN/USAGE.md)
- [常见问题与当前限制](docs/zh-CN/FAQ.md)
- [本版更新](docs/zh-CN/RELEASE.md)
- [开发与构建](DEVELOPMENT.md)
- [文档目录](docs/README.md)

**请注意**：测试预览版。与第三方插件的桥接、地形显示和多人游戏体验仍需多次测试；突然崩溃时经济与领地记录可能需要人工核账，详见常见问题。

## 许可

项目代码使用 **GPL-3.0（GPL v3）**，全文见 [LICENSE](LICENSE)。第三方代码保留原作者与许可，见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。发布附件包含对应源码。
