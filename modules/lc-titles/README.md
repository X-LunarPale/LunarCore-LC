# 称号模块（titles）

Java 25 / Paper 26.3；使用 LunarCore 标准模块加载，无跨模块类引用。默认提供 pioneer、supporter 两个定义，玩家初始不拥有任何称号。

配置 `titles` 为列表，每项包含 `id`（1–48 位小写字母、数字、下划线、连字符）、`display`（最多 256 字符单行）、`permission`（额外佩戴权限，空为无需权限）。列表避免默认 map 补全导致删除定义后被恢复。用 `/lc reload titles` 重载；删除定义保留历史持有关系但停止显示，重新添加同 ID 可恢复。

`/titles`（别名 `/title`）默认列出自己的拥有/佩戴状态和所有可用定义：

- `/titles list [玩家名或UUID]`：查看；查看他人需要 `lc.titles.admin`。
- `/titles equip <id>`：佩戴已拥有称号；额外 permission 必须满足。
- `/titles clear`：取消佩戴。
- `/titles grant <玩家名或UUID> <id>`：授予，需要管理权限。
- `/titles revoke <玩家名或UUID> <id>`：撤销，自动取消对应佩戴；也支持清理已删除定义。
- `/titles create <id> <称号文本...>`：管理员创建全新定义，重复 ID 报错，支持带空格的文本和受限 MiniMessage。
- `/titles update <id> <称号文本...>`：管理员修改已有显示文本，保留该定义的额外佩戴权限。
- `/titles delete <id>`：管理员删除定义并立即停止显示，保留玩家历史持有关系。

`lc.titles.use` 默认所有人，`lc.titles.admin` 默认 OP。离线名仅查询 LunarCore 已知玩家缓存，不访问 Mojang、不生成猜测 UUID；完整 UUID 支持从未加入的离线玩家。带额外权限的称号需玩家在线检查权限后才能佩戴。在线权限变化后最多 1 秒停止显示；持有记录保留。

Web「称号管理」是声明式面板：`titles.view` 查看定义和持有记录，`titles.manage` 管理授予/撤销/佩戴/取消。后端校验字段类型、UUID、操作、ID、持有及额外权限，所有成功修改记审计。Web 也可新增/更新/删除定义（原子保存配置并即时刷新），定义更改同样审计；删除不会丢失玩家历史持有记录。

数据写入模块目录 `players.tsv`：UUID、拥有 ID 列表、佩戴 ID；异步命令写盘、Web 工作线程写盘，原子替换成功后才公布不可变快照。保存失败不修改内存。加载损坏数据时拒绝启动，防止覆盖；不支持文件系统原子替换时修改报错，不降级为可能损坏的写入。无需等待 disable 刷盘，已确认成功的操作均已写入文件；关闭时拒绝后续修改。

变量 `%lc_titles_title%` 返回受限 MiniMessage，`%lc_titles_plain%` 返回纯文本，`%lc_titles_id%` 返回当前显示称号 ID。解析仅读取缓存，可异步调用；离线玩家没有显示缓存。未知变量返回 null。显示允许颜色/装饰/渐变/彩虹/reset；用户提供 click/hover/insert/selector 标签不生效。chat TAB 通过现有 placeholder 注册表获取并再次限制渲染，模板可用 `<title>` / `{title}` / `%lc_titles_title%`。

仅 TAB 显示：本模块只公开显示缓存；chat 模块的 TAB 刷新仅调用 `playerListName`。不设置 `customName`、scoreboard team 前后缀或玩家头顶名称，也不自动向聊天格式插入称号。要求启用 chat 的 `tablist.enabled`，并让 `tablist.name-format` 包含 `<title>`（默认已包含）；若第三方插件自行使用占位符到头顶，须单独移除其配置。创建定义不会自动授予；例如管理员 `/titles create aurora <aqua>极光 守望者</aqua>`，再 `/titles grant 玩家 aurora`，玩家 `/titles equip aurora`。定义与玩家持有均即时保存，正常重启后保留。

针对性检查：`:lc-titles:test :lc-chat:test :lc-titles:jar :lc-chat:jar`。运行服还需检查命令权限、在线/离线授予、实际 TAB 显示、热重载及禁用恢复。
