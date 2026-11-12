# LcPlayerTitleWeb

独立标准 Bukkit `JavaPlugin`，为单独安装且公开 API 能力兼容的 **PlayerTitle** 提供 LunarCore Web 可视管理。Java 25 / Paper 26.3，`depend: LunarCore`、`softdepend: PlayerTitle`。不是 LunarCore 模块，构建产物放普通 `plugins/`；不要放入 LunarCore modules。服务器安装、启停和部署由运维执行。

本桥接全部为独立编写的代码。PlayerTitle 为 ARR 商业软件；仓库不包含其 jar、反编译源码或复制的实现，也不将其 shade。仅通过实际 PlayerTitle 插件 ClassLoader 加载公开 API，并调用公开 static 方法、参数 setter 和返回 DTO getter；没有私有反射、DB、内部服务、命令执行或整数枚举扫描。

## 实际可视界面

登录 LunarCore Web，在「玩家 → PlayerTitle 称号管理」：

- 目录表来自 `findShopTitle(null)` 加上本桥查验/玩家查询后追踪的 ID。**不是全量目录**。公开 API 没有枚举全部定义的方法；非商店称号用「查找 ID」后显示，不猜测 ID。
- Web 创建提供 `NOT`（无条件）与 `ACTIVITY`（活动/管理员发放）两种获取方式。每次显式提交一个对应的公开 `TitleRequireParam`，避免空 requires 列表；这修复了先前尝试空列表时的 vendor 越界错误。请先在测试服用临时名称验证，不要对商业服直接创建批量数据。
- 每个目录行提供「改名」「改描述」表单；「授予玩家」输入已知名字或完整 UUID、定义 ID、授予天数。
- 在线玩家表便于复制 UUID。在持有表搜索框填写玩家已知名字或完整 UUID，显示 API 返回的实际持有记录、定义 ID、名称、到期和当前佩戴状态。每行可佩戴（要求在线）、确认后撤销。
- `playerUseTitle` 的参数是 **TitlePlayer.id 持有记录 ID**；`removePlayerTitle` 是 **TitleList.id 定义 ID**。后端区分并验证持有归属，避免同名整数误用。
- 本地 iframe 预览组件支持 `&0`–`&f`、`&#RRGGBB`、粗体/斜体/下划线/删除线/reset，仅预览，不自动保存。请把预览文本填入管理表单。使用 DOM `textContent`，不执行 HTML，不访问登录 token，不发送请求。字体/前后缀等以实际 PlayerTitle 为准。

表格默认原始文本显示，不将管理员输入作为 HTML/MiniMessage 执行；实际称号格式由 PlayerTitle 公共 API 处理。

## 权限、生命周期与持久化

Web `playertitle.view` 允许目录/ID查验/玩家读取；`playertitle.manage` 允许修改。路由和动作按钮都声明权限；没有任意命令功能。字段校验类型、整数上下限、控制字符、长度及完整 UUID。玩家名只用 LunarCore 现有玩家缓存解析，不请求 Mojang、不猜测 UUID。

每次修改读取 before、调用 API、读取 after，并记录 LunarCore 审计（actor 为 Web 用户）；API false 不视为成功。void 佩戴 API 返回仅表示已调用，界面提示刷新核验，不谎报已落库。API 读回可能受 PlayerTitle 自身异步行为影响，审计会如实记录当时返回状态。

所有 PlayerTitle/Bukkit 操作通过 WebContext.sync 在主线程调用；数据库IO行为由 PlayerTitle 自身决定。目录追踪上限1000，`tracked-ids.txt` 原子替换保存（仅保存已验证ID，称号定义/持有完全由 PlayerTitle 管理）。追踪保存失败记录日志但不会回滚已发生的 vendor 操作。目录每次取真实 API 状态，已删除追踪项不显示。

owner 使用本 JavaPlugin 实例。禁用主动 unregisterAll，PlayerTitle 禁用时注销路由/面板/静态资源，重新启用后重新绑定公开API。LunarCore标准 PluginClassLoader 即可，不需 Paper 专用 loader。PlayerTitle 未安装/公开 API 不兼容时不注册管理界面，日志说明原因。

## 当前佩戴称号占位符

`%lc_playertitle_current%` 返回公开 findPlayerTitle 所示当前佩戴且未到期的titleName，保留 &/§ legacy色码；`%lc_playertitle_current_plain%`去色。无佩戴/无缓存/读取失败返回「未佩戴」，未知key返回null。尖括号文本不执行MiniMessage，消费者需按legacy安全反序列化，不得把该动态结果作为MiniMessage模板解析。

解析只读并发缓存，不在TAB/计分板每次渲染时调用vendor/数据库。在线玩家每20tick主线程读取公开API；join即时刷新，quit清理，Web equip/grant/revoke/delete/rename成功后即时刷新。外部命令变更在下一次周期刷新同步（约1秒，受服务器tick与vendor异步读回影响）。vendor/plugin禁用时取消任务、注销占位与事件并清空缓存；刷新错误按分钟限频日志。此占位只提供当前佩戴文本，不改变PlayerTitle或TAB的显示所有权。

## 公开依据与限制

创建表单仅开放官方文档明确列出的 NOT（无条件）与 ACTIVITY（管理员给予）模式；不开放依赖第三方经济插件的类型。使用的公开 DTO setter 由 `javap -public` 核实。模式分别从公开 `BuyTypeEnum` 获取其枚举显示名，并设置 amount=0、buyData=""、单元素 requires；其余非本功能字段保持默认/显式空 buffs。按插件实际公开 API 与必要签名判断能力是否可用，不预设 vendor 版本白名单；具体方法不兼容时由桥接能力检测关闭入口并记录原因。

- [官方 PlayerTitleApi Javadocs](https://javadoc.handyplus.cn/PlayerTitle/cn/handyplus/title/api/PlayerTitleApi.html)：addTitle/findTitleById/updateTitleName/updateTitleDesc/setPlayerTitle/removePlayerTitle/findPlayerTitle/findShopTitle/playerUseTitle。findShopTitle UUID 标记 Nullable；只代表商店称号。setPlayerTitle day=0 永久。
- [官方 TitleListParam](https://javadoc.handyplus.cn/PlayerTitle/cn/handyplus/title/api/param/TitleListParam.html)。在线文档没有列出 Lombok setter，因此用目标 5.5.4-free jar 的 `javap -public` 核验 DTO/enum 公共签名，没有读取方法字节码或实现。
- [官方文档入口](https://ricedoc.handyplus.cn/wiki/PlayerTitle/)。

每行「删除定义」需确认，后端调用公开 removeTitle 并审计删除前后读回；可能影响玩家记录，请先备份。仅在检测到 PlayerTitle 已启用且所需公开 API 可用时开放桥接；health 返回实际版本与目录范围。版本不设白名单，具体 API 能力仍按公开接口逐项检测。

所有响应在 `ctx.sync` 完成后的请求线程写出；超时后已开始的 vendor 调用仍可能完成，但不再晚写 HTTP 响应。

本轮不实现价格/商城设置、Buff/粒子编辑、取消佩戴：没有请求这些功能或没有足够明确公开契约。没有偷偷访问内部服务补全目录。

## 构建与验证

项目名 `:lc-playertitle-web`；根构建统一提供 Paper 与测试依赖，本项目仅 compileOnly lc-api，不打包依赖。

定向：`./gw.sh :lc-playertitle-web:test :lc-playertitle-web:jar`（由 lead 统一执行，避免并行 Gradle）。单元测试覆盖数字/UUID/文本校验、公开DTO映射、持有ID与定义ID分离及真实表格/iframe面板结构。

未自行启动/停止服务器或部署。仍需运行服验证 vendor API 默认创建参数、权限拒绝、商店空列表、异步读回、佩戴视觉效果、生命周期；前端实际打开检查预览颜色和行操作弹窗。
