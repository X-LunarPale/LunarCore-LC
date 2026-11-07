# 桥接拆分验收 / Bridge split validation

2026-11-07，macOS ARM64，Java 25.0.3，Paper 26.3 build 202。
生成插件 `0.1.0-preview.1`，LC/桥接 `0.0.6-preview`（本 PR 分支，含 NativeGeneratorDelegate）。仅在新建的隔离测试服验证，没有部署到用户运行服。

> 自动重启更新：桥接版本 0.0.6-preview.1 的额外验收见 [自动重启验收](RESTART-VALIDATION.md)。下面保留首次拆分时的历史记录；其中“无脚本可确认停止”已被新版本移除。

## 自动检查

- 生成插件 14 项、桥接 Java 4 项、地形 64 项测试通过。
- 桥接前端 12 项、核心前端 85 项及 i18n 3 项通过；两套 Vite 构建通过。
- 生成插件和桥接分别构建。桥接 JAR 包含 module.yml 和页面资源，不包含 YUNG’s 类、数据包或 Paper 插件描述文件；生成插件不再注册 Web 路由。
- 核对迁移的 4,372 个原文件：生成引擎及资源保持原样；原文件中仅插件入口和 paper-plugin.yml 按拆分修改，另新增公开管理 API、测试及独立构建文件。

## 真实 Paper 与浏览器

- 普通主世界、下界、末地正常启动；LC 模块先启动、生成插件后启用时自动绑定成功。
- 匿名请求返回 401；viewer 对状态、修改、重启均返回 403；管理员可读写。
- 非法配置及旧 revision 返回 400；无待生效修改时重启返回 400。保存仅修改 saved，active 保持原值。
- 模块 reload 保留配置；disable 后接口和静态页面返回 404；enable 后恢复。生成继续运行。
- 未装生成插件的独立启动：状态页面仍可用，返回 available:false；配置读写和重启返回 503。
- 浏览器实测：从导航进入独立 iframe 页面；中文切换到英文，主题切换，390px 移动布局，实际切换河流桥梁并保存，弹出重启说明；取消后服务继续运行。带 `/bridge-check` 子路径。
- 重启请求先返回成功，再停止服务；无 restart-script 时准确返回 canRestart:false。完整重启后 active=saved，显式逐世界 false 保留。
- 新建 Tectonic 世界继承配置，状态为 supported，无生成错误。普通世界与 Tectonic 世界均自然生成橡木矿井，分别记录 43、106 个结构片段。
- 关闭矿井生成并重启，四个世界重新加载成功，Tectonic 自动加载成功。两处采样区块分别比较 98,304 和 163,840 个方块，方块与群系 SHA-256 均保持一致；具体记录在 validation/。

## 边界

接口版本不兼容、缺失方法、旧连接失效、I/O 失败通过隔离 ClassLoader 单元测试验证。没有在正式服执行热卸载生成插件，没有重跑历史报告中的每种结构、完整龙战或其他 Paper 构建；这些不属于本次桥接回归的新增证据。首次沙箱启动因端口权限失败留下的临时世界已另存，实际验收使用重新创建的测试世界。一次矿井探针使用了不存在的通用 ID，随后使用实际 mineshaft_oak ID 在两个世界通过。

## English

Validated on an isolated Paper 26.3 build 202 server with Java 25.0.3. No production deployment. All 82 Java tests and 100 frontend tests passed. Tested authentication/authorization, stale revision rejection, pending versus active settings, module route/resource cleanup and re-enable, missing-plugin failure states, and full-restart persistence. Browser checks covered the actual embedded page, proxy prefix, English/Chinese, dark mode, mobile width, save and restart cancellation.

A new Tectonic world and the ordinary overworld both naturally generated an oak mineshaft. After disabling mineshafts and restarting, all four worlds reopened; sampled blocks and biomes retained identical hashes. See validation/summary.json and paired fingerprint reports. Unsupported management APIs and I/O errors were covered by isolated-ClassLoader tests. Historical engine validation is separate and is not claimed as rerun here.
