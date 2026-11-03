# 反作弊模块（anticheat）

本模块连接独立安装的 GrimAC Bukkit 插件，提供连接状态、近期违规记录、中文可视化配置与桥接管理。检测、VL、豁免、回滚与处罚完全由 GrimAC 管理。原有内置检测及封禁逻辑已移除，旧配置项不再生效。

已按 `grimac-bukkit-2.3.75-b08e9b1.jar` 的公开 API 验证签名；不直接依赖或打包 Grim 类。模块通过 GrimAC 插件类加载器获取 `GrimAPIProvider.get()`，使用 `GrimAbstractAPI.getEventBus()` 订阅 `FlagEvent`。GrimAC 缺失、未启用或 API 不兼容时模块保持可用并显示未连接。

每次绑定创建独立 `BasicGrimPlugin` 所有者；模块停止、重载或 GrimAC 禁用时只注销自身监听器。GrimAC 启用后自动重新绑定。事件会被 Grim 重用，所有数据均在回调中复制为不可变快照；不保留事件、用户、检测对象，也不调用 Bukkit 玩家 API。

## 配置与数据

- `bridge.capture-enabled`：是否采集后台近期违规；不影响 GrimAC 检测或处罚。
- `bridge.recent-capacity`：内存容量，范围 1 至 10000，默认 500。超过容量移除最旧记录，模块停止时清空。详情限制 2048 字符。
- 原有数据库历史表保留原样，不再读取、写入、清理或删除。

## 命令与后台

- `/ac status`：查看连接状态、版本及缓存数，权限 `lbr.anticheat.command`（默认 OP）。
- `/ac reload`：重载桥接配置并重新绑定，另需 `lbr.anticheat.reload`（默认 OP）。
- 检测与处罚设置可在后台的“GrimAC 配置”中编辑，保存到 GrimAC 自身配置文件。
- 后台权限：`anticheat.view`、`anticheat.manage`。
- `GET /stats`：后端连接、版本、采集数、失败数及异步重载状态。
- `GET /violations`（兼容 `/alerts`）：近期快照；支持 `q` 玩家/UUID/检测搜索与 `page`、`size` 分页，单页最多 200。
- `GET/POST /settings`：仅允许修改两个桥接配置项；严格验证开关和整数范围。
- `POST /grim/reload`：管理权限下调用公开 `reloadAsync()`，请求和最终结果分别审计，不阻塞主线程，不执行控制台命令。
- 变量：`%lbr_anticheat_backend%`、`%lbr_anticheat_connected%`。

### 自定义封禁管理界面接口

以下均为模块相对路径，完整前缀为 `/api/ext/anticheat`。

- `GET /health`（view）：对象字段 `backend, connected, status, version, captureEnabled, cached, capacity, captured, captureFailures, reloadStatus`；原 `/stats` 卡片数组仍保留。
- `GET /commands`（view）：`{artifact, commands}`。每项含 `id, label, command, permission, grimPermission, supported, reason, args`。参数含 `name, label, type, required, options, min, max`。`permission` 是网页操作权限，`grimPermission` 是原生命令权限。
- `POST /commands/run`：`{"id":"profile","args":{"target":"Steve"}}`。只接受声明的操作和参数；只读操作需 view，上传报告、告警发送、配置重载、调试切换、存储维护需 manage。返回 `{ok,id,executionId,command,output,pending,started,captureExpiresAt}`；`ok` 仅说明 Bukkit 接受命令，不代表 Grim 异步任务成功，最终结果在 output 中。
- `GET /commands/runs/{id}`：读取同一网页账户的执行输出，并再次检查该操作权限。输出窗口 5 分钟，执行记录保留 10 分钟；`pending` 表示输出窗口仍开启，并非 Grim 任务完成状态。建议执行后短时轮询并保留手动刷新。每次请求最多 200 行、64 KiB，单行最多 4096 字符；最多缓存 200 次执行。

固定命令目录由指定 JAR 的 `jar tf` 与 `javap -p -c -v` 验证，实际命令注册使用 Cloud builders，无命令注解。支持 help、version、perf（别名 performance）、profile、list（必须指定 players 或玩家）、history（别名 hist，含 player、page、session、detailed/verbose 与 name/match/grep 过滤分支）、reload、debug（网页必须指定目标，切换该目标的控制台调试）、consoledebug、dump、log（别名 logs 和 gl，必须提供 flagId）、sendalert、testwebhook、history repair check-ids、history migrate（可选 delete）。所有固定根命令另有 grimac 别名，网页统一使用 grim。

注册但不支持网页执行的功能也完整显示：alerts/brands/verbose 涉及玩家订阅或共享控制台状态；spectate/stopspectating 要求游戏内玩家。history copy 的注册存在，但本构建 `DataStoreLifecycle.allBackendsForCommands()` 固定返回空 `Map.of()`，无法提供有效来源与目标，故元数据明确标为不支持。

命令参数不接受选择器、换行、额外参数或原始命令字符串。历史过滤限制为 128 字符的单 token 正则字符集。网页命令使用独立的 Bukkit CommandSender 代理捕获消息，权限查询委托服务器控制台；代理不实现 ConsoleCommandSender，避免 Grim 将输出路由到共享控制台，也不混用原生与 shaded Adventure Audience，以免动态代理接口返回类型冲突。字符串与原生 Adventure 组件输出均按请求捕获。网页 debug 操作映射到 consoledebug，维持切换目标控制台调试的行为；不会安装全局日志监听器、冒充玩家或修改控制台告警订阅。请求、权限拒绝、验证拒绝、命令提交与执行异常均审计。异步任务业务结果由输出展示，提交审计不等同于业务成功。

### GrimAC 中文可视化配置

入口位于“封禁与反作弊 → GrimAC 配置”。读取和保存均需要 `anticheat.manage`，配置数据来自已安装 GrimAC 插件的实际数据目录。

- `config.yml`：主配置，包含告警、客户端识别、移动与战斗检测、回弹阈值和网络兼容设置。
- `punishments.yml`：处罚规则，按现有分组编辑检测列表、违规过期时间、触发等级、重复间隔和执行命令；间隔为 `0` 表示仅执行一次。
- `messages.yml`：消息文本与列表，保留 GrimAC 的变量和文本格式。
- `discord.yml`：Discord 告警、消息内容与 Webhook。
- `database.yml` 与已有 `databases/*.yml`：历史存储、后端路由与连接设置。

字段显示中文名称、分组、说明和原始配置键，可以按名称或键搜索。密码、Webhook 和可能含凭据的连接字符串不会返回浏览器；只有主动修改或清空时才写入。模式标记和不受支持的字段保持只读。

配置按文件读取，不在后台定时扫描。保存只提交修改项，验证类型、数值范围及处罚命令格式，并通过配置指纹拒绝覆盖其他位置的新修改；写入前保留原文件备份。未修改的设置和 YAML 注释继续保留。

- `GET /grim/config`：配置文件目录与初始文件快照。
- `GET /grim/config/{fileId}`：读取文件快照、字段元数据与指纹。
- `POST /grim/config/{fileId}`：提交 `{"fingerprint":"sha256:…","changes":{"配置键":"新值"}}`，返回保存后的快照。

保存配置与“重载 GrimAC”分开执行；重载使用已有公开异步 API，并查询最终重载状态。当前核对的 GrimAC 构建支持数据库后端重载，期间会暂时停止历史写入，超过排空超时的队列条目可能被丢弃；请在负载较低时应用存储设置。重载成功表示 GrimAC 报告重载完成，不代表已做真实玩家检测或数据库连接验收。

## 验证

`bash ./gw.sh :lbr-anticheat:test --console=plain`

单元测试覆盖容量边界、并发写入、过滤、不可变快照和事件重用。实服仍需验证独立 GrimAC 安装、插件启停、模块重载及正常玩家操作。
