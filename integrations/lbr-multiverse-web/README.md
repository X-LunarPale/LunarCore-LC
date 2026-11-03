# LbrMultiverseWeb：独立多世界面板

独立Bukkit JavaPlugin，通过LunarCore的WebRegistry注册真实声明式面板。不是core内置模块；不复制或重新分发Multiverse源码/JAR。依赖LunarCore，可选Multiverse-Core；检测到已启用的Multiverse-Core后自动接入，不设版本白名单。缺少、停用或所需公开API不可用时仍保留健康状态面板，操作失败关闭。插件禁用时owner=this统一注销；MV启停事件会记录日志，请求每次重新取得API，不保留过期vendor实例。

## 安装与构建

仓库根settings需要将`:lbr-multiverse-web`映射本目录（由lead统一处理）。使用仓库Java25环境运行`:lbr-multiverse-web:test :lbr-multiverse-web:jar`。API和Paper均compileOnly，不shade进产物。将独立LbrMultiverseWeb.jar与自己的Multiverse-Core 5.8.1放入测试服plugins，再正常重启；不是放modules目录。本实现不自动安装vendor、不启停服务器。

## 实际面板

Web登录后进入管理→多世界管理。专用工作区提供按世界名或别名搜索、分页列表、当前配置编辑、加载/卸载及安全传送。配置编辑使用别名输入框和布尔开关，一次保存当前世界的四项受支持属性；提交时校验读取快照，检测到并发修改会要求重新读取。旧的单属性 configure 请求仍兼容。

| 路由 `/api/ext/multiverse` | 权限 | 范围 |
|---|---|---|
| GET /status、/worlds | multiverse.view | API健康、已注册世界、加载/人数、别名/PVP/天气/自动加载 |
| POST /configure | multiverse.configure | 单属性 `name/property/value` 或批量 `name/values/expected`；只允许alias、pvp、allowWeather、autoLoad |
| POST /lifecycle | multiverse.lifecycle | name/operation，只load/unload已注册世界 |
| POST /teleport | multiverse.teleport | name/player，精确在线玩家至已加载世界出生点 |

配置接口拒绝未知字段、非布尔开关值、控制字符和超长别名。`expected` 必须包含完整配置快照，防止编辑器覆盖其他管理员的并发修改；`row` 不作为权威字段。别名支持清空。所有Bukkit/MV操作经ctx.sync主线程，操作记录LunarCore审计。

加载/卸载通过MV WorldManager和UnloadWorldOptions，卸载保存Bukkit世界，并拒绝Bukkit第一世界、MV默认世界和任何有玩家世界。不会强踢/转移玩家。无创建、导入、删除、移除配置、再生成、任意文件编辑或命令执行入口。

配置调用public setter并检查Vavr Try.isSuccess，再检查saveWorldsConfig；失败尝试恢复内存旧属性，仍报错，不声称事务性磁盘回滚。MV某些属性可能即时改变世界，应在测试服验证后授权。

传送调用MV公开AsyncSafetyTeleporter，checkSafety=true，出生点来自MV。响应仅表示已提交，不假报到达；异步成功/失败另写teleport-success/teleport-failed审计。不会跳过Bukkit传送事件或强制绕过保护。传送是Web管理员授权动作，MV物理安全检查不等同所有传送命令的经济/目标世界访问策略；没有声称完整复制/mv tp的权限/费用行为。建议只将该Web权限授可信管理员；插件可能因玩家乘客关系影响其他实体，实服需核实。当前使用仍公开但已标deprecated的teleport(Entity)，5.8.1返回单项AsyncAttempt，未来6.0不保证。

## 来源与核实边界

- 官方仓库：https://github.com/Multiverse/Multiverse-Core
- 官方固定标签：https://github.com/Multiverse/Multiverse-Core/tree/5.8.1
- 官方API文档入口：https://mvplugins.org/core/developers/api-usage/
- 官方源码归档：https://codeload.github.com/Multiverse/Multiverse-Core/zip/refs/tags/5.8.1

2026-10-24：raw.githubusercontent.com在当前环境DNS失败，gh工具无token；codeload成功下载5.8.1官方源码归档，并核对AsyncSafetyTeleporterAction安全检查/异步结果与deprecated边界，临时归档已移除，未打包厂商代码。用户提供multiverse-core-5.8.1.jar通过javap核实MultiverseCoreApi、WorldManager、MultiverseWorld、UnloadWorldOptions、Attempt、AsyncAttempt公开签名；这是静态API核对，不证明已运行。

## 测试与人工验收

单测覆盖公开签名反射重载/异常传播、输入拒绝；lead执行本目录compile/test通过（实服证据由lead单列）。仍需人工逐项填写，不预填通过：

- 待测：无MV/停用MV/版本不符健康面板正常，各操作拒绝；重新启用自动恢复，插件卸载后面板/权限消失。
- 待测：只读角色不能修改配置/生命周期/传送；未知属性、未知字段、换行注入和非精确世界名拒绝。
- 待测：列表含空别名、未加载世界；分页不报错，显示不暴露磁盘目录/seed等无关信息。
- 待测：四项配置变更、重启持久化、setter/save失败不假成功；普通世界行为与MV一致。
- 待测：主世界/MV默认世界/有玩家世界不能卸载；空测试世界卸载/加载后区块和存档完整。
- 待测：在线玩家传送安全出生点、危险目标、保护取消、离线玩家、未加载世界；响应提交与最终审计对应。
- 待测：骑乘/乘客、快速重复操作、vendor停用期间请求以及桥接禁用时异步回调；有失败保留脱敏日志。

不宣称覆盖MV全部命令/配置，不宣称Paper26.3兼容性仅凭编译成立，也没有性能提升承诺。
