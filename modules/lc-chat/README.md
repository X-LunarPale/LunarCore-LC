# 聊天与社交模块 (chat)

聊天格式、反刷屏/敏感词/广告过滤、私聊、邮件、昵称、广播与自动公告、MOTD、加入/退出消息、管理频道、Tab 列表。

## 命令
| 命令 | 别名 | 权限 | 默认 |
|---|---|---|---|
| /msg <玩家> <消息> | tell, w, m, whisper, pm | lc.chat.msg | 所有人 |
| /reply <消息> | r | lc.chat.reply | 所有人 |
| /msgtoggle | togglemsg | lc.chat.msgtoggle | 所有人 |
| /ignore [玩家] / /unignore <玩家> | | lc.chat.ignore | 所有人 |
| /mail read\|send\|sendall\|clear | | lc.chat.mail / .send / .sendall | 所有人（sendall 为 OP） |
| /nick <昵称\|off> [玩家] | nickname | lc.chat.nick（.color / .others） | OP |
| /realname <昵称> | | lc.chat.realname | 所有人 |
| /broadcast <消息> | bc, bcast, announce | lc.chat.broadcast | OP |
| /motd、/rules | | lc.chat.motd / rules | 所有人 |
| /helpop <消息> | ac | lc.chat.helpop（接收：lc.chat.helpop.receive） | 所有人 |
| /me <动作> | action, emote | lc.chat.me | 所有人 |
| /clearchat | cc | lc.chat.clearchat（豁免 .exempt） | OP |
| /staffchat [消息] | sc, adminchat | lc.chat.staffchat | OP |
| /chatmute | mutechat, globalmute | lc.chat.chatmute（绕过 .bypass） | OP |
| /socialspy | spy | lc.chat.socialspy | OP |
| /lcchat | | lc.chat.admin（重载） | OP |

其它权限：`lc.chat.color`（聊天颜色）、`lc.chat.url`（可点击链接，默认所有人）、`lc.chat.item`（[item] 展示物品，默认所有人）、
`lc.chat.bypass.filter|spam|ad`、`lc.chat.ignore.exempt`、`lc.chat.msg.bypass`（无视对方屏蔽/关闭私聊）、`lc.chat.silentjoin`、`lc.chat.format.<组>`。

## 变量
`%lc_chat_nick%`、`%lc_chat_mail_unread%`

## 说明
- Paper 下使用 AsyncChatEvent + ChatRenderer；Spigot 下回退 AsyncPlayerChatEvent。
- 禁言拦截由 ban 模块负责；本模块在私聊、/me 中检查 BanService。隐身判定来自 essentials 模块（EssentialsService）。
- Web：聊天记录、实时推送（topic `chat`）、广播、全服禁言、配置表单。

## 游戏内 AI 聊天

支持主角色与最多四个附加角色；各自独立设置人格、供应商、模型、密钥和单个回退模型。`@名称` 选择对应角色；主动回复按概率随机选择已启用角色。回退限一次，适用于网络错误、404、408、429 和 5xx；鉴权及余额错误不会回退。可关闭失败回复，或设置以该角色聊天格式发送的固定失败消息。

模型列表、余额、嵌入接口仅由服务器调用，均需 `chat.ai.manage` 权限。DeepSeek 余额使用官方 `https://api.deepseek.com/user/balance`，sub2API 使用配置版本根路径下的 `/usage`；中转站不提供该接口时显示查询失败，不推测余额。面板可按一分钟间隔更新余额，隐藏页面或编辑未保存配置时暂停。自动获取模型使用 `/models`，不会自动猜测任意中转站真实上游。

语义记忆参考 Hindsight Memory 的选择性保存、敏感信息过滤和隔离召回思路，独立实现，不依赖 Hindsight 云服务，也不宣称实现完整 Hindsight。默认关闭；使用管理员配置的兼容 `/embeddings` 接口，按玩家 UUID 与角色 ID 隔离，最多保留 24 条明确自述的偏好，30 天清理。当前采用保守的中英文规则，普通闲聊、模型回复、命令及识别出的敏感内容不持久化为记忆；规则过滤不能保证识别所有敏感信息。记录写前去重；更换嵌入地址或模型后旧向量不参与召回。检索失败不阻断聊天。启用后相关玩家消息会额外发送到嵌入供应商；可通过面板按玩家或全量清除记忆。此策略只约束 AI 记忆，聊天日志仍遵循独立的 `log.enabled` 和 `log.keep-days`。

默认关闭。管理员在 WebUI 的“AI 聊天”中填写人格、显示名、兼容 Chat Completions 的 Base URL、模型与 API Key 后开启。Base URL 是版本根路径，例如 `https://api.openai.com/v1`，模块向其 `/chat/completions` 发送请求。密钥仅写在本地 `plugins/LunarCore/modules/chat/config.yml`，GET 接口只返回 `keyConfigured`，浏览器不回显密钥；更换 Base URL 必须重新填写密钥或先清除旧密钥。请限制服务器文件读取权限并优先使用 HTTPS。配置该服务意味着触发的玩家公开聊天内容与角色设定会发送到所选供应商。

默认 `mention`：公开聊天包含 `@月灵`（或管理员设定的 AI 名）时才可能回复。`all` 为主动模式：未点名的公开聊天按 `proactiveProbability`（0–100%）独立抽样，被点名则不抽样、仍受全服冷却与单飞限制。默认概率 100% 保持原 `all` 行为，建议按费用调低；未抽中的消息不消耗冷却或发起模型请求。全服同时最多一条请求，冷却 5–600 秒，输入截断 500 字，输出最多 240 字；聊天记录最多保留 64 位玩家最近三轮，20 分钟后丢弃，不写入持久对话库。HTTP 连接与请求有超时，不跟随重定向；只有原聊天未被禁言或过滤时才触发。模型输出以纯文本组件插入默认玩家聊天模板，不解析为 MiniMessage；AI 不是真实玩家，不可执行命令，也不接收私聊。WebUI 保存设置会立即重载运行中的聊天配置，无需为每次修改重启；首次部署新插件 JAR 则仍需按服务器部署流程启动/重载模块。

WebUI“聊天管理”的 `defaultChatFormat` 可自定义 `format.groups.default.format`，要求包含 `<displayname>` 和 `<message>`，支持 `{player}`、`{world}`；AI 回复沿用该格式，但不继承触发玩家的 `%...%` 个人占位符，这些变量在 AI 发言中留空。AI 配置接口为鉴权的 `GET/POST /api/ext/chat/ai/settings`，需要独立权限 `chat.ai.manage`；保存 Key 使用 `apiKey`，留空保持原值，`clearApiKey: true` 清除。授予 `chat.ai.manage`（或 `*`）相当于允许该账号控制服务商配置及密钥用途，仅应授予可信管理员。

## 资源占用

AI网络客户端在首个实际请求时才创建，禁用、繁忙或冷却时在聊天文本序列化及主线程派发前退出。请求执行器最多一个工作线程、一个等待任务，线程空闲30秒后退出；公开聊天在派发前先预留唯一请求位置。重载使旧回复失效，但会等待旧请求结束后才接收新请求，不会立即取消正在进行的HTTP请求。

聊天记录待写队列最多5000条，写入期间另持有最多5000条的批次；刷盘任务串行执行。数据库失败时尝试回填旧批次，优先保留队列中已经接收的新记录；超出容量的记录丢弃并汇总告警。该队列是有界的尽力记录，不是数据库故障下保证不丢失的持久消息队列。

## TAB 配置

`tablist.enabled` 默认 true；`refresh-seconds` 默认 5，运行时限制为 1–3600 秒。`header` / `footer` 为多行 MiniMessage，`name-format` 默认 `<title> <displayname>`，留空恢复原列表名。旧配置已有键保留，需自行将称号加入原 name-format。模板支持 `{player}`、`{world}`、`{ping}`（毫秒）、`{online}`、`{max_players}`、`{tps}`（1分钟，最高20）、`{mspt}`；TPS/MSPT 显示两位小数。

称号支持 `<title>`、`{title}`、`%lc_titles_title%`，通过 titles 注册的现有 placeholder 解析，没有新的 API/硬依赖；未启用 titles 时为空。其他 `%lc_...%` 变量以普通文本插入。昵称按安全组件插入，世界名/变量文本不会成为 MiniMessage 指令；只有服主模板自身可使用完整 MiniMessage。所有 Bukkit TAB 更新均在主线程，复用单一刷新任务；reload 重建周期，disable/关闭TAB恢复接管前的 header/footer/name，退出清理缓存。其他插件同时修改 TAB 时后写入者生效。

Web 聊天配置可改开关、刷新秒数、header/footer/name（`chat.manage`，后端校验并审计）；header/footer 最多8192字符，名称格式最多1024字符。针对性测试 `:lc-chat:test` 覆盖刷新间隔上下界与 Web 类型/数值边界。实际 Paper 客户端显示及其它 TAB 插件交互需运行服验证。
