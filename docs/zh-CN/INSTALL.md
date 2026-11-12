# 安装与升级

[English](../en-US/INSTALL.md) | [首页](../../README.md)

## 需要准备

使用 Java 25、Paper 26.3，以及 `LunarCore-0.0.7.jar`。首次启动需访问 Maven Central 下载运行依赖。

## 首次安装

1. 停服，把核心 JAR 放入 `plugins/`。
2. 启动服务器，等待插件启用。
3. 在服务器所在电脑打开 `http://127.0.0.1:8765`。
4. 按控制台提示登录，随后修改管理员密码。
5. 在模块市场安装需要的功能，下载完成后完整重启。

默认只有基础命令。家、传送申请属于传送模块；聊天与 AI 属于聊天模块，需单独安装。

## 从旧版升级

1. 停服，备份 `plugins/LunarCore/`、服务端配置和世界。
2. 替换 `plugins/` 中的旧核心，同一目录只保留一个 LunarCore JAR。
3. 启动后检查账号、世界与模块状态。
4. 在市场逐组更新模块并重启；也可停服后手动替换同版本模块。

已有附加模块不会自动删除。核心升级只自动更新内置基础命令。已自定义的面板名称和 Logo 保留；恢复默认名称后显示“LunarCore 琉月”。

内部插件名仍为 `LunarCore`，数据目录保持原样，无需移动。

## 手动安装模块

下载与核心同版本的模块 JAR，停服后放入 `plugins/LunarCore/modules/`。更新时替换原文件，不保留重复副本。建筑地标还需要领地模块。

全部附加模块也可从 `LunarCore-modules-0.0.7.zip` 按需解压。安装更多模块会增加资源占用。

地形模块还需将 `LC-Terrain-Agent.jar` 放到服务端目录，在 Java 启动命令的 `-jar` 前添加 `-javaagent:LC-Terrain-Agent.jar`，然后完整重启。详见[地形模块说明](../../modules/lc-terrain/README.md)。

## 结构美化插件

从独立插件仓库下载 `LunarCoreStructures.jar` 放入 `plugins/`，另将 `lc-structures-web.jar` 放入 `plugins/LunarCore/modules/`。核心和生成插件须满足[桥接兼容要求](../../modules/lc-structures-web/README.md)。九类结构默认关闭；在“服务器 → 结构美化”保存设置后完整重启。支持全服默认及按世界设置，只影响新区块和新世界。安装说明见[结构美化](STRUCTURES.md)，实测范围见[验证记录](STRUCTURES-VALIDATION.md)。

## 远程访问面板

默认仅允许本机访问。需要远程访问时，在 `plugins/LunarCore/config.yml` 调整地址。公网使用 HTTPS 反向代理，即由 Nginx 或 Caddy 等入口转发至本机面板；入口需支持 WebSocket，才能显示实时日志。

修改 `web` 配置后执行 `lc web reload`。不要把明文面板端口直接开放到公网。

## 依赖下载失败

检查网络。如果默认 Maven 镜像校验失败，可在 Java 启动命令的 `-jar` 前添加：

```text
-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=https://repo.maven.apache.org/maven2
```

保留文件校验，不使用跳过校验的参数。
