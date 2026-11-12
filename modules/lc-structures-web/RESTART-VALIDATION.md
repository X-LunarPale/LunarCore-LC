# 自动重启验收 / Automatic restart acceptance

2026-11-07：桥接 `0.0.6-preview.1`，生成插件 `0.1.0-preview.1`，Paper 26.3 build 202，Java 25.0.3，macOS ARM64。沿用隔离测试世界，未更改运行服脚本。

## 本轮结果

- 桥接 Java 6 项、前端 14 项测试通过；模块 JAR 与 Vite 构建成功。
- 无 restart-script 时重启接口返回 400，原进程继续运行；配置保存仍可用。
- 有待生效配置且脚本可读时接受一次请求，重复请求返回 400。
- 排队后移走脚本，延迟任务取消重启；原进程和 Web 保持运行，restartQueued 恢复 false。
- 浏览器选择“稍后重启”后保持运行，之后通过“立即重启生效 → 确认重启”完成自动重启。
- 实际自动启动前 PID=15150，启动脚本拉起 PID=15356；检测到 HTTP 断线及新的 JVM 启动时间。四个世界重新加载，active 与保存的 revision=6 一致，restartRequired=false。
- 浏览器无需重新登录或刷新，自动显示“服务器已重新启动，结构设置已生效”。成功依据为新进程，不是 POST 的 ok 响应。
- 单元测试确认相同 JVM、暂时断线不能报告成功；排队取消、120 秒超时及生成插件不可用时不会自动重试重启请求。

原始脱敏结果：`validation/automatic-restart-prechecks.json`、`validation/automatic-restart-result.json`。

## 测试边界与一次失败尝试

首次从临时 PTY 启动测试服时，Paper 已接受重启并退出，但脚本没有启动子进程，45 秒恢复检查失败，页面没有误报完成。随后改为独立后台进程启动测试服，并让启动脚本忽略 HUP，再完成上述端到端验证。不能将第一次仅停服算作成功，也不能据此保证任意终端/托管面板的脚本均可用。

脚本存在且可读不代表内容正确。生产启动命令、Windows 批处理、容器/托管面板的启动方式仍须按实际环境配置。模块不会自行改写它们。旧合体插件未正式发布使用，因此旧版本迁移演练不列为此次自动重启验收前置条件。

## English

Tested bridge 0.0.6-preview.1 on macOS, Java 25.0.3 and Paper 26.3 build 202. Six Java and fourteen frontend tests passed. Missing scripts and duplicate requests were rejected; removal during the queue cancelled shutdown. A real browser confirmation triggered Paper's restart hook: a new process (15356, replacing 15150) was launched by the script, all four worlds loaded, revision 6 became active, and the page automatically confirmed recovery without refresh/login.

An earlier PTY-launched attempt stopped without relaunching and is recorded as a failure. The passing attempt used a detached background server and HUP handling in the script. File presence cannot validate arbitrary script contents. Windows/container/hosting launch procedures are not claimed as tested. No production server configuration was changed.
