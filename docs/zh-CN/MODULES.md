# 模块使用

[English](../en-US/MODULES.md) | [GitHub 模块目录](../../module-market/README.md)

模块是一组可单独安装的功能。核心内置 `essentials` 基础命令，其他 19 个模块按需安装。

## 使用市场

1. 进入“模块管理 → 模块市场”，搜索或选择分类。
2. 点击“安装”或“更新”，必需的依赖模块会一起下载。
3. 出现“等待重启”后，完整停止并重新启动服务器。
4. 回到“已安装”确认状态。

每次只处理一组变更。可先“取消变更”再重新选择。市场只提供与当前核心匹配的官方模块；更新核心见[安装与升级](INSTALL.md)。

## 卸载

卸载只移除 JAR，不删除模块配置、数据库和世界。仍被其他模块依赖时，需先卸载依赖者。基础命令不能在市场卸载。

## 公开下载与投稿

v0.0.7 的源码、模块市场与发布附件位于公开仓库 [LunarCore-LC](https://github.com/X-LunarPale/LunarCore-LC)。网页下载和面板安装无需登录 GitHub，也不需要令牌。

所有人都可以通过 PR 或 Issues [投稿模块](../../CONTRIBUTING.md)。社区作者在自己的公开 Release 上传文件，市场目录提供下载链接。社区模块目前需手动安装；面板一键安装提供与核心一起发布的官方模块。

## 需要额外配置的功能

- 建筑地标必须安装领地模块。
- 签到的金币奖励、付费领地购买和出售物品需要经济模块。
- 反作弊需要另装兼容的 GrimAC。
- AI 需要模型服务地址、模型名与密钥。
- 性能优化默认关闭。
- QQ 认证需要群号与机器人连接，安装后不会自动启用。
- 地形模块需要 `LC-Terrain-Agent.jar` 和启动参数，见[安装说明](INSTALL.md)。
- 结构美化管理模块需要另装 `LunarCoreStructures.jar`，生成插件不包含在核心中，见[结构美化](STRUCTURES.md)。

QQ 认证配置完成后，停服把 `modules.qqauth-autostart` 设为 `true`，并仅从 `modules.disabled` 中移除 `qqauth`，保留其他禁用项。启用或重载可能断开玩家连接。

## 独立插件

`LcMultiverseWeb.jar` 与 `LcPlayerTitleWeb.jar` 放入 `plugins/`，分别需要 Multiverse 或 PlayerTitle。它们不是内部模块，也不包含第三方插件本体。
