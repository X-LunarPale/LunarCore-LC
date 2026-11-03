# v0.0.6 测试预览版

[English](../en-US/RELEASE.md) | [首页](../../README.md)

## 变化

- 统一名称为 LunarCore 琉月，简称 LC，主命令为 `/lc`，保留旧入口与原数据目录。
- 核心只内置基础命令；19 个附加模块独立提供。
- GitHub 提供模块目录和独立下载；面板支持安装、更新、卸载、取消与依赖提示。
- 独立公开仓库提供源码与附件，无需令牌；社区模块通过 PR 或 Issues 自由投稿。
- 变更在完整重启后应用，卸载保留配置与数据。
- 简化用户说明，提供中英文使用文档。
- 使用 GPL v3，保留第三方许可与对应源码。

## 附件

| 文件 | 用途 |
|---|---|
| `LunarCore-0.0.6-preview.jar` | 核心，放入 `plugins/` |
| `lbr-*.jar` | 附加模块，放入 `plugins/LunarCore/modules/` |
| `LunarCore-modules-0.0.6-preview.zip` | 全部附加模块 |
| `LunarCore-0.0.6-preview-source.zip` | 源码与构建文件 |
| `LunarCore-0.0.6-preview.zip` | 核心、模块、源码与安装说明 |
| `LbrMultiverseWeb.jar`、`LbrPlayerTitleWeb.jar` | 可选独立插件 |
| `module-market.json` | 版本、依赖与文件校验信息 |
| `SHA256SUMS.txt` | 下载文件校验值 |

文件名中的 `0.0.6-preview` 即“v0.0.6 测试预览版”。公开仓库从本版开始。

升级前停服备份，步骤见[安装](INSTALL.md)。测试结果见[验证记录](VALIDATION.md)；真实游戏体验需另行验收。
