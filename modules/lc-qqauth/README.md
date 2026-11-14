# QQAuth 模块：QQ 群验证与游戏账号认证

[English](README.en.md)

独立 LC 模块，安装到 `plugins/LunarCore/modules/lc-qqauth.jar`，不放在 `plugins/` 根目录。需要 Java 25、Paper 26.3 与同版本 LunarCore。账号、密码哈希和绑定使用 LunarCore 数据库。

**不迁移任何密码库。不要与 AuthMe 或原独立 QQAuth 插件同时启用。** 玩家首次使用新账号库时重新注册；已有 QQ 绑定可单独导入。此实现覆盖下表中的认证流程，不宣称与 AuthMe 全功能等价。

## 自定义所有提示

在面板中编辑提示消息，或修改模块目录 `config.yml` 的 `messages:`。可修改进服提示、验证码踢出页、定时 ActionBar、命令反馈、QQ群回复、账号状态说明及 Web 文案。旧配置启动时自动补齐默认项，保留已有配置。

```yaml
messages:
  login:
    bind-required: |
      欢迎 {player}
      请到群 {group} 发送 /验证 {code}
      验证码有效期 {ttl} 秒
  join:
    instructions: "欢迎 {player}，请先登录或注册。"
  reminder:
    login: "请使用 /login <密码> 登录"
```

也可将文本写成 YAML 字符串列表，每项显示为一行。游戏内支持 MiniMessage 颜色、文字样式与 `&` 颜色代码；QQ 和 Web 的纯文本预览会去除样式。占位符只替换一次，不执行命令。

| 配置项 | 可用占位符 |
|---|---|
| login.bind-required / identity-recovery | {player}、{code}、{group}、{ttl}（秒） |
| join.instructions / session-restored、reminder.login / register | {player} |
| command.unbind-code / reset-code | {code} |
| command.failed、group.failed、web.error.operation-failed | {status}（取自 status.auth / binding 配置） |
| group.usage | {command} |
| group.success | {command}、{player} |
| command.admin-status | {ready}、{count} |
| command.admin-error | {error}（异常类型，不含异常详情） |

面板保存提示消息后立即生效，无需重载，也不会断开玩家连接。其他配置保存后点击“应用配置”，或在控制台执行 `qqauth reload`。直接编辑配置文件后也需执行该命令。重载会重新连接 QQ 桥接，并断开在线玩家，玩家需重新连接和登录。

“重新读取”只读取已保存的配置，不会重载模块。AstrBot 网络故障提示仍在 AstrBot 插件设置中的 `error_reply` 配置。

## 已实现流程

| 功能 | 行为 |
|---|---|
| 强制 QQ 验证 | 默认开启。未绑定玩家无法进入，显示验证码；指定 QQ 群发送 `/验证 <码>` 后重新连接 |
| 可选 QQ 验证 | 关闭 `binding.required` 后，未绑定玩家可注册、登录并正常游戏；登录后用 `/qqauth bind` 申请绑定码 |
| 每日提醒 | 可选模式下，未绑定玩家登录后每天提醒一次；持续在线跨天也会提醒。重连、重载和重启不重复提醒；绑定后停止 |
| 内置注册/登录 | `/register <密码> <重复密码>`、`/login <密码>`，别名 `/reg`、`/l`；注册后直接认证 |
| 未登录限制 | 冻结行走/飞行能力并取消位移、跳跃、飞行/冲刺/滑翔启动及外加速度，保留转头；认证/离线/禁用恢复本模块仍持有的原速度与飞行设置；禁止聊天、普通命令、交互、放置破坏、伤害、物品拾取丢弃、库存操作、桶、书/告示牌编辑和载具进入；OP 也不绕过。Bukkit无法保证客户端零移动预测/零回弹 |
| 登录超时 | 未认证玩家约 120 秒后踢出；每 5 秒显示操作提示 |
| 改密/注销 | `/changepassword <旧密码> <新密码>`、`/unregister <密码>`；须已登录并完成 QQ 验证，成功后踢出重新认证；注销不删除 QQ 绑定 |
| 退出登录 | `/logout`，留在游戏内并恢复未登录限制 |
| QQ 恢复密码 | 未登录时 `/qqauthreset password <新密码>`，绑定 QQ 在群里发送 `/改密 <码>`；成功后旧认证失效 |
| QQ 解绑 | 登录后 `/unbind` 拿码，绑定 QQ 在同一群发送 `/解绑 <码>`；成功后踢出 |
| 管理员 | `/qqauth status`、`reload`、`forceunbind <玩家>`、`lock <玩家>`、`unlock <玩家>`、`delete <玩家>`；权限 `qqauth.admin` 默认 OP，但玩家执行仍须已认证 |
| Web | 账号列表、登录/锁定状态、锁定/解锁/删除注册/重置密码/解除绑定；后端权限 `qqauth.view`、`qqauth.manage` |
| 可选重连会话 | 默认关闭；开启后只允许相同 UUID、名字、IP、账号版本及固定 TTL 内重连，无重启持久化 |
| 名称身份 | 名字 3–16 位字母数字下划线；大小写无关唯一；账号身份模式可在面板设置，允许切换时需验证原密码后更新 UUID 与 QQ 绑定 |

密码默认 10–64 字符，UTF-8 最多 72 字节，BCrypt cost 12。仅保存哈希；QQ 待改密码也只保留已哈希值和账号版本。改密、锁定、删除与重建通过版本轮换撤销旧认证与旧恢复请求。认证及哈希使用 2 个工作线程和 128 项队列，超载拒绝。

可选验证设置：

```yaml
binding:
  required: false
  reminder:
    enabled: true
    timezone: "" # 留空使用服务器时区，也可填 Asia/Shanghai 等时区
```

未绑定玩家不能自助改密、恢复密码、注销或解绑；管理员的账号救援操作保留。提醒文字在 `messages` 中自定义，面板可预览。

## 正版与离线身份

如果只有 QQ 绑定、尚未注册密码，切换认证方式后会显示恢复码。使用原绑定 QQ 在原群发送 `/验证 <码>`，再重新连接并注册密码。恢复会保留原 QQ、群和绑定时间，不占用新的绑定名额。密码账号已更新、QQ 绑定仍旧的情况也可通过此流程恢复。

在 QQAuth 配置的“账号身份模式”中选择，也可修改 `auth.identity-mode`，保存后点击“应用配置”：

| 值 | 模式 | 行为 |
|---|---|---|
| `auto` | 自动（跟随服务器） | 默认。正版服务器使用严格 UUID；离线服务器使用玩家名与密码 |
| `uuid` | 严格 UUID | 只接受账号原 UUID，适合保持稳定 UUID 的服务器或代理 |
| `name` | 玩家名与密码 | 按大小写无关的玩家名查找原账号，验证原密码后更新 UUID 与 QQ 绑定 |

切换离线模式或名字大小写后，使用原密码登录即可完成认证身份更新；不会仅凭同名自动继承账号。此项只处理 QQAuth 账号与 QQ 绑定，不迁移背包、位置、权限或其他插件的玩家数据。

带密码命令在最早事件阶段取消并改为脱敏文本再自行处理，以减少服务端常规命令日志记录。不要装不可信的命令监听/抓包插件；模块无法保证排在更早的第三方监听器不读取原始包。控制台不提供带明文密码的管理员命令，改用受权限保护的 Web 重置操作。

## 安装与配置 AstrBot

1. 停服备份，移除 AuthMe 与旧独立 QQAuth 插件。保留旧绑定文件作备份，不导入旧密码。
2. 将模块 JAR 放到 `plugins/LunarCore/modules/`，启动；也可使用内置 QQAuth 的整包，但不要重复放模块。
3. 首次生成模块目录 `plugins/LunarCore/modules/qqauth/` 和 config.yml，其中 bridge token 自动生成，不打印 token。
4. 设置 `integration.group-id` 为真实群号或官方机器人 group_openid；默认占位值拒绝群请求。QQ 官方 sender openid 是不透明身份，不是真实 QQ 号码。
5. AstrBot 和游戏服直接运行在同一台机器时，默认地址 `http://127.0.0.1:12312/qqauth/group` 可用。Docker 或异机中的 `127.0.0.1` 指向 AstrBot 自己；需改为游戏服可达地址，并调整 `integration.astrbot.bind-address`、开放或映射桥接端口。`12312` 与 Web、游戏和 RCON 端口独立；实际监听地址见游戏服启动日志。
6. 把 [AstrBot 插件目录](<bridge/astrbot_plugin_qqauth/README.zh-CN.md>)整体安装到 AstrBot 的 `data/plugins/`。支持 QQ 官方机器人和 OneBot/NapCat，配置 URL、相同 token、明确 group_id；默认命令 `/验证`、`/解绑`、`/改密`，自定义别名会转换为服务端命令。地址省略路径时自动补齐 `/qqauth/group`。
7. 从服务器控制台执行 `qqauth reload`，或在面板点击“应用配置”。在线玩家会断开连接，需重新进入服务器并登录。

Java 端允许 `group-id: "*"`，表示信任认证桥接的群声明；建议明确群 ID。AstrBot 插件要求明确群 ID，不接受空或星号转发。OneBot/NapCat 填群号，QQ 官方机器人填 `group_openid`；两种身份不会自动转换。AstrBot 使用 OneBot/NapCat 时，QQAuth 仍选择 `astrbot` 模式。

OneBot 模式将 `integration.mode` 设为 `onebot`，配置正向 WebSocket endpoint、token、重连间隔；JDK WebSocket 实现自动重连并在模块关闭时取消连接。`disabled` 仅停止 QQ 传输；是否允许未绑定玩家进入由 `binding.required` 决定。

QQ 验证码按用途隔离，默认 6 位，TTL 绑定/解绑 600 秒、恢复 300 秒；绑定码同一 UUID 重连复用并刷新 TTL。默认每 QQ 一账号，可用 `binding.per-qq-limits` 覆盖。错误群/错误 QQ 不消费解绑与改密码；成功解绑清除所有待操作，重启/重载清除内存验证码。群发送者默认每 5 分钟最多 10 次验证请求。

## 单独导入旧 QQ 绑定

旧 `bindings.yml` 不被自动读取，操作必须明确执行：

1. 将旧文件复制为模块目录下 `import-bindings.yml`。
2. 配置明确的群 ID，确认每 QQ 配额允许导入条数。
3. 控制台执行 `qqauth importbindings confirm`。

识别旧结构 `bindings.<uuid>.name / qq / bound-at`；旧秒时间戳转换为毫秒。导入是追加且整批事务，UUID、大小写无关名字或配额冲突使整批失败，不会覆盖现存记录。成功后请移走导入文件。原 bindings.yml 保持不变。

## Web 接口

接口前缀 `/api/ext/qqauth`，沿用 LunarCore Web 登录身份：

| 方法与路径 | 权限 | 内容 |
|---|---|---|
| GET /stats | qqauth.view | 就绪状态与账号数量 |
| GET /accounts | qqauth.view | 分页搜索，返回名字/UUID/锁定/认证/创建时间，不返回密码、哈希、IP、QQ 标识、token |
| POST /accounts/{name}/lock、unlock、delete、forceunbind | qqauth.manage | 更改账号或绑定并撤销旧认证 |
| POST /accounts/{name}/reset | qqauth.manage | JSON password 字段；通过有界哈希队列设置新密码 |

桥接接口独立于 Web 端口：POST `/qqauth/group`，`X-QQAuth-Token` 头，JSON 字符串字段 `group_id`、`sender_id`、`message`。错误 token 401，格式错误 400，超过 64KiB 413；普通消息不处理。此 token 与 Web JWT 不是同一凭据。

## 测试和已知边界

执行 Gradle `:lc-qqauth:test`；Python 执行 `python -B -m unittest discover -s modules/lc-qqauth/bridge -p test_bridge.py -v`。Java 用真实 SQLite、真实本地 HTTP/WebSocket 与 Bukkit 事件对象覆盖关键流程，未接触生产 QQ 群。

待人工验收：真实 AstrBot/QQ 机器人端到端绑定、客户端注册登录/未登录限制、双连接与改密竞态、与其他模块的登录提示/传送兼容、MySQL 实例。数据库实现支持单服务器缓存；多个运行中服务器共用账号表需要额外缓存失效机制。

模块禁用会踢出当前在线玩家；禁用后监听被卸载，后续连接不再受本模块保护。不要在开放登录的正式服关闭认证模块后继续接待离线玩家。首次启用时也会踢出已有在线玩家重新验证。

未实现邮箱、TOTP/2FA、验证码图形挑战、代理/正版自动认证、AuthMe 协议或密码库迁移、自动执行认证后玩家命令。旧 QQAuth 在 QQ 群内验证的身份凭据仍是安全边界。

QQAuth 由 LunarCore 作者与 [qqauth-private](https://github.com/crx97220130101-dotcom/qqauth-private) 的协作者共同开发。该模块随 LunarCore 以 GPL v3 发布；既有贡献者署名与许可说明保留在 `META-INF/QQAuth-UPSTREAM-LICENSE` 和 [bridge/LICENSE](<bridge/LICENSE>)。
