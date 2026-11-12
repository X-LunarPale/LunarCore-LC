# Scoreboard 模块

Java25/Paper26.3 标准 LunarCore 模块，无跨模块实现类引用。默认右侧sidebar；island基于Adventure BossBar，加载可选资源包后使用专用bitmap字体呈现黑色圆角胶囊和定宽文字；未加载/拒绝/失败时保留原版顶部信息条。未全局覆盖原版BossBar纹理，不修改其他插件资源包。胶囊覆盖自己原版条为字体位置校准效果，仍需客户端验证；多BossBar堆叠位置由客户端决定，不能保证固定首行或任意像素位置。

## 玩家与权限

`/scoreboard sidebar|island|off|default`（别名sb），`lc.scoreboard.use`默认所有人。default删除个人override、跟随管理员默认模式；选择立即显示并原子写模块目录player-modes.tsv。写盘失败不发布新偏好，损坏文件拒加载防覆盖。玩家记录保留离线偏好，显示对象quit清理。

`/scoreboard reload`需要lc.scoreboard.admin，默认OP。无游戏命令创建/修改任意目标，无displayName/TAB/头顶改写。称号/聊天模块保持各自职责。

## 配置

- display-enabled: true（总显示开关）
- default-mode: sidebar，支持sidebar/island/off
- title: MiniMessage侧边栏标题，最多256字符
- lines: 0–15行，每行最多512字符，重复/空行支持
- island-frames: 1–100帧，每帧最多512字符
- update-ticks:20，严格10–1200整数
- rotate:true，rotation-ticks:100，严格20–72000整数
- boss-color:BLUE（Adventure BossBar颜色枚举）；boss-progress:1，严格有限0–1

静态管理员模板允许MiniMessage；内置{player}/{world}/{online}/{ping}/{tps}/{mspt}与注册表/PAPI变量以unparsed组件插入，玩家/扩展变量不能注入click/hover等标签，变量产生的标签将作为文字显示。TPS为1分钟数据（最高20），MSPT为Paper平均tick耗时。所有Bukkit/占位符/计分板更新主线程，一个受追踪timer，失败按玩家隔离、最多每分钟警告一次。

sidebar仅在玩家使用main scoreboard且原板没有任何teams/objectives时接管，保留原板引用；其他插件设置非main板后不抢回。原main后来增加team/objective时立即归还。恢复仅当当前板仍等于本模块own时执行，绝不覆盖后来第三方新板。独立own objective与稳定entry、Score.customName渲染，重复行不冲突，NumberFormat.blank隐藏数字，更新不reset所有行，仅删除缩短行。quit/disable/reload/mode switch恢复自身占用并释放引用。

island每玩家独立BossBar，切换、退出、禁用隐藏自己的bar，不操作其他插件bar。轮播按实际配置tick累进；更新间隔比轮播间隔长时会跳帧，建议update-ticks不大于rotation-ticks。

## Web

正式结构化面板「计分板显示」含状态Stats和完整配置Form。scoreboard.view查看，scoreboard.manage保存，所有路由独立鉴权：

- GET /api/ext/scoreboard/settings
- POST /api/ext/scoreboard/settings：完整JSON enabled/defaultMode/title/lines/frames/updateTicks/rotationTicks/rotate/color/progress；lines/frames换行字符串。
- GET /api/ext/scoreboard/status：[{label,value},...]侧边栏/信息条/外部板占用计数。

后端严格类型/数值/行数/控制字符校验；Web保存先原子写配置再发布，失败保留显示；成功审计before/after。主线程返回数据后请求线程响应。模块清理自动注销路由/面板/权限。

## 可选胶囊资源包

初始配置启用资源包功能但不主动推送，服主需先在 Web 管理页配置客户端可访问的 HTTP/HTTPS 直链并启用自动发送。`/scoreboard pack` 可手动请求；加入后20tick追加发送。独立resource-pack.yml含pack-enabled/auto-offer/url，不改变原有默认sidebar与玩家选择。Web提供资源包设置表单与玩家加载状态；GET/POST `/api/ext/scoreboard/pack-settings` JSON enabled/autoOffer/url，GET pack-status分页状态，GET pack-info为id/url/sha1/loaded/available。Web保存会移除本包并按配置重新发送；`/scoreboard reload`只重载主显示配置，手改resource-pack.yml需模块重载，推荐Web保存。

资源包直链应配置为客户端可访问的 HTTP/HTTPS 地址，下载内容必须与内置 island-pack.properties SHA1 完全一致。资源包通过固定UUID追加、optional不强迫；模块关闭仅remove自己的UUID，不清其他pack。只在对应UUID且本轮已请求的SUCCESSFULLY_LOADED事件后启用字形；ACCEPTED/DOWNLOADED仍保持原版，拒绝/失败/丢弃回退。摘要缺失/无效不会发包或输出PUA乱码。

字体命名lunar:island/lunar:mono/lunar:space。背景320×30拆为两片160宽glyph（E000/E002），避开客户端256字体图集单glyph限制；每片advance161，中间-1得到321，再回退321并按真实字宽居中。文字不再强制17px间距：读取内置island-widths.properties（HEX=advance）逐字测宽；动态组件先纯文本化，最多32 Unicode codepoints且272px预算，字宽表没有的字符替换□。字宽表缺失或无有效□时不发送资源包/不启用增强，保持原版。固定白字、非粗体、无阴影，居中使用正负space；因此原模板颜色/交互事件不进入胶囊。PNG覆盖标准条、GUI缩放/客户端字体、其他资源包优先级都需客户端实测，不能把服务端JSON状态当作视觉验收。

## 验证

JUnit涵盖配置类型/边界与textarea roundtrip、动态文本注入、玩家偏好重载/default与写入失败不publish。Lead统一定向compile/test及部署，本实现未自行执行全量Gradle。运行服仍需在线客户端确认sidebar/岛条视觉、外部插件争用、模式切换、退出及热重载；无在线玩家的REST读回不能替代视觉验证。
