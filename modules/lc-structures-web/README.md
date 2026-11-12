# 结构美化 Web 桥接模块

[English](README.en.md)

`lc-structures-web.jar` 是 LC 模块（ID `structures`），放入 `plugins/LunarCore/modules/`。它不包含 YUNG’s 生成代码或资源，不能替代生成插件。

## 安装

1. 停服，将兼容核心和独立仓库 [rtiags/LunarCoreStructures](https://github.com/rtiags/LunarCoreStructures) 发布的 `LunarCoreStructures.jar` 放入 `plugins/`。
2. 将桥接 JAR 放入 `plugins/LunarCore/modules/`，完整重启。
3. Web 中打开“服务器 → 结构美化”。全服默认、逐世界覆盖、继承、保存后重启功能与原页面一致。

要求 Java 25 / Paper 26.3，LunarCore 含 `NativeGeneratorDelegate` API，生成插件提供管理 API v1。旧版合体插件没有此接口，页面会提示升级。兼容核心必须来自本桥接 PR；不能仅凭相同的 `0.0.6-preview` 版本文字判断兼容。

升级保留 `plugins/LunarCoreStructures/structures.json`，替换原生成 JAR，不能同时保留两份。配置只由原插件写入；保存不会改变已运行的生成器，完整重启后作用于新区块。自动重启要求服务端配置可读的 restart-script；脚本缺失时只提示手动重启，服务器继续运行。保存后可立即确认，也可稍后点击“立即重启生效”。页面通过 JVM 启动时间确认服务已重新启动；请求超时不会重复发起重启。配置示例见 [自动重启](RESTART.md)。

## 生命周期与协议

模块启用时尝试绑定生成插件，并监听其启停。缺失、停用或 API 不兼容时保留状态页面、禁止写入。模块重载重新绑定；禁用时清理页面、路由、监听器和待执行重启任务。禁用桥接不会停止结构生成；生成插件本身不支持热卸载。

所有接口沿用 `core.server.manage` 权限：`GET /api/ext/structures/status`、`GET/POST /api/ext/structures/config`、`POST /api/ext/structures/restart`。未连接时 status 返回 `available:false/message`；配置和重启返回 503。非法配置或过期 revision 返回 400；I/O 失败返回 500，记录服务器日志。

通过实际插件 ClassLoader 调用 `getManagementApi()` 的公开 API v1，仅传递 JDK Map/List/基本值；不引入原插件类或 NMS。世界状态经主线程查询，配置保存沿用原插件的同步锁、revision 检查和原子写入。修改和重启写入 LC 审计。

页面通过 `registerStatic/registerPanel` 注册，资源随模块打包，不使用 CDN。它与管理面板同源，读取现有登录 token，支持反向代理子路径、中英文和主题切换；登录失效后停止写入并提示重新登录。

## 构建与投稿

仓库根执行 `./gradlew :lc-structures-web:test :lc-structures-web:jar`（JDK 25，Node/npm）。Gradle 自动执行 `npm ci` 和 Vite 构建。前端测试：`cd modules/lc-structures-web/web && npm test`。产物 `build/libs/lc-structures-web.jar`。

桥接作为社区扩展发布，不增加官方 19 模块目录。发布完成后，使用 `tools/prepare-structures-market.py` 以实际 JAR、版本及公开下载 URL 生成市场条目，再执行 `python3 tools/validate-module-market.py`。`depends` 仅表示 LC 模块依赖，独立生成插件在说明中声明。

本轮验证见 [VALIDATION.md](VALIDATION.md)。
