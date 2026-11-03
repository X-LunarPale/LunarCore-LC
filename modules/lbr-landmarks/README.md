# 建筑地标册 / Building landmarks

自愿投稿的建筑目录，依赖 `land` 模块。它不传送玩家，不发放奖励，也不改变世界资源。

## 玩家操作

- `/landmarks`：打开已审核、当前玩家有权进入的地标列表，每页45条。
- `/landmarks help`：显示命令。
- `/landmarks mine`：查看自己的全部投稿、审核状态与驳回原因。
- `/landmarks submit 标题 | 简介`：在自己拥有的领地内记录当前方块坐标。
- `/landmarks edit ID 标题 | 简介`：修改自己的标题和简介，再次进入待审核状态；原坐标不变。
- `/landmarks withdraw ID`：撤回自己的投稿，释放一个活跃投稿名额。

标题1至48个UTF-16字符，简介最多240个；以一个 `|` 分隔标题与简介，简介可空。
不接受控制字符、格式控制符、MiniMessage标签和Minecraft颜色标记。
普通玩家默认拥有 `lbr.landmarks.use`，游戏内没有审核或传送命令。
修改与重新提交要求原坐标仍在投稿者拥有的原领地内。编辑已撤回的记录可重新投稿，但仍须满足活跃投稿上限。

## 显示规则

仅公开 `APPROVED` 条目。每次打开、点击和每秒检查已打开菜单时，重新查询当前领地服务：
世界必须已加载并在精确世界名单中，原领地ID和拥有者必须一致，记录坐标必须仍位于该领地的XYZ范围内。
必须由实际浏览玩家通过 `LandService.can(player, location, ENTER)` 检查；不会以后台身份代替玩家。
领地服务不可用、领地删除或转让、缩小移出坐标、世界卸载或玩家被拒绝进入时，条目会隐藏。
不自动加载世界或区块。菜单使用模块专属InventoryHolder，取消底部物品移动、Shift点击、拖拽和双击。

## 配置

```yaml
enabled: true
worlds: [world]
max-per-player: 3
```

`worlds` 最多64个精确世界名，不支持 `*`，空名单表示所有世界均不展示且不接受投稿。
Web表单每行填写一个世界，留空对应空名单。
`enabled: false` 停止公开目录与新投稿；投稿者仍可查看、撤回已有条目，管理员仍可管理记录和配置。
`max-per-player` 范围1至12，按非 `WITHDRAWN` 条目计数，包含待审核与已驳回投稿。
全服最多保存20,000条历史记录，撤回不会删除历史记录。缩小上限不会删改已有记录。
严格拒绝未知键、错误类型和非法世界名；配置重载失败时保留之前的有效配置。

## Web接口

面板：`/panel/landmarks/landmarks`。所有接口均在 `/api/ext/landmarks` 下，无匿名目录接口。
`landmarks.view` 允许查看记录与配置，`landmarks.manage` 允许审核、撤回和配置写入。
只拥有观察者基础权限的账户无法访问；协管的 `*` 可使用审核功能。

- `GET /stats`：待审核、已公开、位置有效和展示状态统计。
- `GET /entries?status=all&page=1&size=20&q=`：返回 `{total,items}`。状态为 `all|pending|approved|rejected|withdrawn`。
  页码1至1,000,000，每页1至100，搜索最多120字符；`sort=id|updatedAt`，`order=asc|desc`。
  非法已知查询参数返回400。未知查询参数由路由忽略。
- 列表行包含 `id,revision,title,description,owner,ownerUuid,landId,world,x,y,z,coordinates,status,availability,createdAt,updatedAt,reviewer,reviewNote`。
  `availability=AVAILABLE|HIDDEN` 只表示位置、拥有权及展示范围是否有效，玩家可见性还需实际玩家的ENTER检查。
- `POST /entries/{id}/approve|reject|withdraw`：`{"row":{"id":1,"revision":1},"reason":""}`。
  Web可提交完整行，但服务器仅取路径ID、行ID和版本，重新读取真实拥有者、坐标、文本及状态。
  驳回原因必填且最多240字符。过期版本409，不存在404，非法状态或参数400。
- `GET /config`、`POST /config`：`{"enabled":true,"worlds":"world\nworld_nether","max-per-player":3}`。

审核通过前复核当前拥有权和坐标，已公开投稿再次编辑会回到待审核。
Web审核权限只面向管理人员，因此管理列表可见全部状态和坐标；它不作为玩家公开目录。

## 保存与恢复

`landmarks.properties` 使用有版本的结构化快照。数据与配置写入使用同目录临时文件、刷盘与原子替换。
专用单线程队列串行处理写入，只有成功保存后才发布新内存快照；命令成功消息和审核响应也在保存后发送。
Web等待仅发生在HTTP线程，Bukkit和领地服务查询在服务器主线程执行。
关闭模块时停止接收任务并排空已接收写入，不等待任何主线程回调。
损坏、超大或未知格式的数据不会自动清空，模块保持不可操作并记录错误，需管理员修复备份后重载模块。
写盘失败不修改内存，后续操作可以重试；超时响应要求刷新确认，防止重复投稿。

游戏语言文件为 `lang/zh_CN.yml` 和 `lang/en_US.yml`，沿用服务器的语言服务。
纯模型、权限边界、版本冲突、配置校验、原子文件保存和关闭队列排空由本模块单元测试覆盖；
真实Minecraft客户端的菜单显示、玩家ENTER规则和实际领地变更需要服务器联调验收。
