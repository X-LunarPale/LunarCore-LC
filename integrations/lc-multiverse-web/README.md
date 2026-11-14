# Multiverse 多世界面板

[English](README.en.md)

在 LunarCore 面板管理 Multiverse-Core 的世界、别名、PVP、天气和自动加载，支持保存并卸载空世界、传送在线玩家。

## 安装

将 `LcMultiverseWeb.jar` 与官方 Multiverse-Core 放进服务器 `plugins/` 目录，更新 LunarCore 后完整重启。桥接插件使用 Multiverse 5 的公开接口，不包含 Multiverse 本体。

未安装、停用 Multiverse 或接口不兼容时，面板显示原因。恢复 Multiverse 后可重新读取状态。

## 宏伟地形

更新 `lc-terrain.jar` 后，新建及已登记的地形世界自动加入 Multiverse，无需手动 `mv import`。

多世界面板可直接加载、卸载地形世界，自动加载开关也会保存到地形模块。MV 自身的自动加载保持关闭，由 LC 恢复原生生成参数。世界仍使用原目录和维度标识。

永久删除地形世界请进入“宏伟地形”，选择“删除世界”并输入完整名称。主世界、MV 默认世界及有在线玩家的世界不能卸载或删除。

## 权限与接口

接口前缀为 `/api/ext/multiverse`，权限由 LunarCore 面板账号管理。

| 接口 | 权限 | 功能 |
| --- | --- | --- |
| `GET /status`、`GET /worlds` | `multiverse.view` | 状态与世界列表 |
| `POST /configure` | `multiverse.configure` | 别名、PVP、天气和自动加载 |
| `POST /lifecycle` | `multiverse.lifecycle` | 加载或保存并卸载 |
| `POST /teleport` | `multiverse.teleport` | 将在线玩家传送到安全出生点 |

传送通过 MV 安全检查，执行结果写入操作记录。普通世界由 MV 管理，原生地形世界的加载由地形模块负责。

## 构建

使用 Java 25，在仓库根目录运行：

```text
gradlew :lc-multiverse-web:test :lc-multiverse-web:jar
```

官方项目：[Multiverse-Core](https://github.com/Multiverse/Multiverse-Core)。
