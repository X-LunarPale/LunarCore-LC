# 琉月·冰澜 LunarCore Web 面板（web-ui）

Vue 3 + Vite + Vue Router + Pinia + Element Plus，图标 lucide-vue-next。构建产物 `dist/` 会被根项目打包进插件 jar 的 `web/` 目录，由插件内置的 Javalin 服务器提供。

## 构建

```bash
cd web-ui
npm ci            # 首次或依赖变更后（使用 package-lock.json）
npm run build     # 输出到 web-ui/dist
cd ..
./gw.sh build     # 打包插件，dist 会进入 jar 的 web/
```

若 Windows 上构建时报 `esbuild ... remove .../Temp/esbuild-xxx: Access is denied`，可将临时目录指向可写位置后重试：

```powershell
$env:TEMP="D:\dsh work\_tools\tmp"; $env:TMP=$env:TEMP; npm run build
```

## 开发

```bash
npm run dev       # http://localhost:5173 ，/api 与 /ext 代理到 http://127.0.0.1:8765
LC_API=http://服务器:8765 npm run dev   # 代理到其它地址
```

## 部署说明

- `vite.config.js` 中 `base: './'`：后端在返回 `index.html` 时注入 `<base href="{base-path}/">` 和
  `window.__LC_BASE__`，因此同一份构建产物可部署在任意 `web.base-path`（如 nginx 反代到 `/lc`）。
- 前端路由使用 HTML5 history，后端对未知路径回退到 `index.html`。

## 目录

| 路径 | 说明 |
|---|---|
| `src/api.js` | HTTP 客户端（JWT Bearer）、WebSocket（`/api/ws?token=`，`{"op":"sub","topic":"console"}` 订阅） |
| `src/stores/` | 登录态 / 权限判断（支持 `*`、`economy.*`、`*.view`、`-node`），主题与扩展面板列表 |
| `src/views/` | 内置页面：登录、仪表盘、玩家、控制台、服务器操作、模块、插件、操作日志、核心配置、账号与角色、个人设置 |
| `src/views/PanelView.vue` | 通用 WebPanel 渲染器入口（`/panel/:namespace/:id`） |
| `src/components/panel/` | Table / Form / Stats 区块、字段输入、行/工具栏操作执行器 |

## WebPanel 渲染约定（与 `lc-api` 中 `WebPanel` Javadoc 一致）

- **Table**：`GET endpoint?page=&size=&q=&sort=&order=` → `{"total":N,"items":[...]}`（也接受直接返回数组）。
  列类型：`text number money datetime(毫秒) bool player(UUID 或名字，mc-heads.net 头像) tag(字符串或数组) duration(毫秒)`。
  `sortable` 列为服务端排序。
- **Action**：`confirm` 文本中的 `{列key}` 会替换为行数据；`endpoint` 中的 `{列key}` 会被 URL 编码替换；
  有 `fields` 时先弹窗收集；请求体 `{"row":{...}, ...字段}`（工具栏操作 row 为 `{}`）；`method=GET` 时字段作为查询参数。
  响应 `{"ok":true,"message":"..."}` 显示提示，`{"ok":false}` 显示警告，4xx/5xx 显示 `error`。
  `permission` 不满足时隐藏按钮。
- **Form**：`GET loadEndpoint` → `{字段:值}`；`POST saveEndpoint` 提交 `{字段:值}`。字段类型
  `text number bool select textarea password player duration("1d2h" 原样提交) datetime(毫秒时间戳) code`。
- **Stats**：`GET endpoint` → `[{"label","value","icon","hint"?}]`，`refresh(秒)` 定时刷新。
- **Iframe**：`url` 以 `/` 开头视为站内路径（自动加 base-path），相对路径视为 `/ext/{namespace}/...`。
- **Text**：简易 Markdown（标题、列表、引用、代码、表格、链接、图片），HTML 会被转义。
- `span` 1-24 栅格；`icon` 为任意 lucide 图标名（kebab-case，如 `gavel`、`pickaxe`），找不到时回退 `box`。
