# 开发与构建

[English](DEVELOPMENT.en.md) | [使用说明](README.md)

本页面向修改代码或编写扩展的人，安装插件无需执行这些步骤。

## 构建

使用 JDK 25 和 Node.js/npm。首次构建需要联网下载依赖。

```bash
cd web-ui
npm ci
npm run build
cd ..
./gradlew test :lc-core:jar :terrain-agent:jar :lc-multiverse-web:jar :lc-playertitle-web:jar :lc-structures-web:jar
python package-release.py
```

Windows 使用 `gradlew.bat`。产物位于 `dist/0.0.8-pre.1/`。核心只内置基础命令，其余模块单独构建。

若 Paper 本地工具处理失败，可用 `-Plc.paperServerHome=服务端目录` 指向匹配版本的完整 Paper 安装。普通构建无需该参数。

本地原生构建按服务端 JAR 的 SHA-256 匹配该目录中的 Paper 启动包，并读取包内依赖清单；请保留启动包。不会混用 `libraries/` 中其他构建遗留的库。结构美化移植及恢复验证见 [STRUCTURES-VALIDATION](docs/zh-CN/STRUCTURES-VALIDATION.md)。

## 测试

```bash
./gradlew test
cd web-ui
node --test tests/*.test.js tests/*.test.mjs
npm run test:i18n
```

浏览器检查使用 `tools/verify-module-market-ui.py`，需要本地前端与 Playwright。脚本只使用模拟数据，不操作运行服。

## 目录与约定

`lc-api/` 提供接口，`lc-core/` 提供面板和基础服务，`modules/` 为功能模块，`integrations/` 为独立插件，`web-ui/` 为前端。

模块通过 API 通信，不直接引用其他模块实现。数据库查询和下载不阻塞游戏线程；访问 Bukkit 玩家、世界与模块启停时回到主线程。模块停止时释放任务、线程和连接。

界面提供中英文，用户说明只写用途、条件与结果。实现细节放在代码或开发资料。

简称统一为 LC，主命令为 `/lc`，变量使用 `%lc_模块_变量%`，扩展入口为 `LC.get()`。旧命令、变量和 `LC` 入口继续兼容。已有 `lc.*` 游戏权限、数据标识、项目目录和模块文件名保持不变，避免升级后权限或数据失效。

## 发布

版本由 `gradle.properties` 决定：`0.0.8-pre.1`，对应 GitHub 标签 `v0.0.8-pre.1`。

`module-market/catalog.json` 定义名称、说明、分类和必需模块。构建自动记录文件大小与 SHA-256（用于检查文件是否一致的校验值）。发布必须上传同次打包的模块；模块重建后也要重建核心，不能混用产物。

普通 Bukkit 插件与内部模块分开安装。二进制发布附对应源码、构建文件、GPL v3 与第三方许可。

公开发布仓库为 `X-LunarPale/LunarCore-LC`。市场直接下载公开 Release 文件，不需要访问令牌。`python tools/validate-module-market.py` 检查社区目录，`python tools/test-module-market-validator.py` 检查投稿校验逻辑。公开快照与源码包只包含当前文档，不包含旧交接记录或运行数据。

结构生成工程已独立发布到 https://github.com/rtiags/LunarCoreStructures 。本仓库仅构建 `:lc-structures-web` 管理模块；其 npm 前端由 Gradle 自动打包。生成插件另行编译并依赖本仓库发布的 `lc-api`，见独立仓库 README。

## 空缓存验证

两个工程已在全新 Linux runner 上完成空缓存构建。CI 链接、隔离方式和可复制的复现命令见[空缓存构建验证](docs/zh-CN/CLEAN-BUILD.md)。
