# LunarCore 琉月模块市场

[English](README.en.md) | [安装说明](../docs/zh-CN/MODULES.md) | [全部下载](https://github.com/X-LunarPale/LunarCore-LC/releases/tag/v0.0.8-pre.1)

[社区模块](community/) · [提交模块](../CONTRIBUTING.md) · [填表投稿](https://github.com/X-LunarPale/LunarCore-LC/issues/new?template=module-submission.yml)

需要 Java 25、Paper 26.3 。核心只带基础命令，以下模块按需安装。

## 安装方式

推荐使用面板“模块管理 → 模块市场”。手动安装时，停服后将模块 JAR 放入 `plugins/LunarCore/modules/`，再启动服务器。

**⚠注意：这里是LunarCore可选装的模块，并不是Minecraft服务器插件！不要直接放入 `plugins/`。**

更新时替换旧文件，不保留重复 JAR。卸载只删除 JAR，配置和数据可以保留。依赖模块必须一起安装。

## 生存

| 模块 | 用途 | 下载 |
|---|---|---|
| 经济 | 余额、转账、排行榜与可选 Vault 支持 | [lc-economy.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-economy.jar) |
| 签到 | 每日签到与奖励，金币奖励需经济模块 | [lc-signin.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-signin.jar) |
| 领地 | 圈地、成员与保护，付费购买需经济模块 | [lc-land.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-land.jar) |
| 玩法投票 | 玩家投票选择玩法变化 | [lc-vote.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-vote.jar) |
| 探索手册 | 记录生物群系、进度和钓获 | [lc-journal.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-journal.jar) |
| 建筑地标 | 投稿、审核与浏览建筑，必需领地模块 | [lc-landmarks.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-landmarks.jar) |

## 玩家

| 模块 | 用途 | 下载 |
|---|---|---|
| 传送 | 家、传送申请、传送点与出生点 | [lc-teleport.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-teleport.jar) |
| 聊天与 AI | 私聊、邮件、聊天格式、多 AI 与记忆 | [lc-chat.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-chat.jar) |
| 称号 | 创建、授予、撤销与佩戴称号 | [lc-titles.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-titles.jar) |
| 计分板 | 侧边栏与顶部信息条 | [lc-scoreboard.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-scoreboard.jar) |

## 保护

| 模块 | 用途 | 下载 |
|---|---|---|
| 处罚 | 封禁、禁言、警告与历史 | [lc-ban.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-ban.jar) |
| 反矿透 | Paper 配置与挖矿告警 | [lc-antixray.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-antixray.jar) |
| 反作弊 | GrimAC 状态与中文配置，需另装 GrimAC | [lc-anticheat.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-anticheat.jar) |
| 游戏权限 | 用户组、临时权限与查询 | [lc-permissions.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-permissions.jar) |
| QQ 认证 | 群验证与账号认证，默认关闭 | [lc-qqauth.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-qqauth.jar) |

## 服务器

| 模块 | 用途 | 下载 |
|---|---|---|
| 世界管理 | 创建、加载、卸载与配置世界 | [lc-world.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-world.jar) |
| 宏伟地形 | 独立世界、气候与地形查找；需[启动补丁](../modules/lc-terrain/README.md) | [lc-terrain.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-terrain.jar) |
| 性能 | 指标、清理和可选优化，优化默认关闭 | [lc-performance.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-performance.jar) |
| 服务器列表 | 列表介绍文字、颜色与变量 | [lc-motd.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-motd.jar) |

## 其他下载

- [核心插件](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/LunarCore-0.0.8-pre.1.jar)
- [全部附加模块](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/LunarCore-modules-0.0.8-pre.1.zip)
- [对应源码](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/LunarCore-0.0.8-pre.1-source.zip)
- [文件校验值](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/SHA256SUMS.txt)

GrimAC、Multiverse 与 PlayerTitle 的管理入口/桥接需要其独立插件，需放入 `plugins/` 并安装对应第三方插件，详见[模块使用](../docs/zh-CN/MODULES.md)。

## 发布与收录

所有人都可以上传自己的模块：在自己的公开 Release 上传 JAR，然后通过 PR 将模块条目加入 `community/`，也可使用 Issues 投稿表单。填写用途、适用版本、依赖、许可、源码和下载链接，提交前可运行附带的目录校验脚本。

上方 19 个官方模块支持面板一键安装，社区模块下载后手动安装。投稿目录通过 PR 合入。本项目代码使用 GPL v3，第三方许可保留。
