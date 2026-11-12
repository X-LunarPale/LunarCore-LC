# 自动重启 / Automatic restart

需要 `core.server.manage` 权限。保存结构设置后可选择“确认重启”或“稍后重启”；之后也可以点击“立即重启生效”。更改未保存时先保存，不能通过重启按钮丢弃草稿。

桥接使用 Paper 的重启机制，读取 `spigot.yml` 的 `settings.restart-script`。**脚本必须由服主配置，并保留服务器原有 Java 路径、内存和启动参数**。使用宏伟地形时不要漏掉 `-javaagent:LC-Terrain-Agent.jar`。模块不生成或改写运行服启动脚本，不接收网页传入的命令。

```yaml
settings:
  restart-script: ./start.sh
```

Linux/macOS 的 `start.sh` 示例（把 Java 命令替换为当前服务器实际使用的命令）：

```sh
#!/bin/sh
trap "" HUP
cd "$(dirname "$0")" || exit 1
sleep 2
exec java -Xms1G -Xmx2G -jar paper.jar --nogui >> server-console.log 2>&1
```

脚本路径建议使用无空格的相对路径，脚本须可读；Paper 使用 `sh` 调用，不能依赖交互终端的输入。先在停服时独立验证启动命令能启动正确的服务器。修改 spigot.yml 后完整重启以加载配置。

Windows 使用 `restart-script: ./start.bat`，批处理示例：

```bat
@echo off
cd /d "%~dp0"
timeout /t 2 /nobreak >nul
java -Xms1G -Xmx2G -jar paper.jar --nogui
```

托管面板、容器和服务管理器请使用其支持的重启方式；若不提供适用的 Paper 重启脚本，就使用手动重启提示，不要同时配置两个互相竞争的启动机制。

## 行为与失败处理

- 脚本缺失或不可读：保存仍成功，页面仅提示手动重启；直接请求重启接口返回 400，服务器继续运行。
- 请求重复：已有排队请求时拒绝第二次请求。
- 确认后延迟 40 ticks 调用重启，尽量先返回响应；执行前再次检查脚本、插件连接和待生效配置。脚本移除则取消并记入审计。
- 页面显示“重启已安排”，以新的 JVM 启动时间确认真正重启；只有配置也已生效才显示完成。模块重载不会误判成进程重启。
- 断线期间自动检查恢复状态，120 秒未确认则提示检查服务端面板，不自动重复提交重启。脚本存在不代表脚本内容一定正确。
- 生成插件管理 API v1 不变。桥接状态额外返回 `restartQueued`、`serverStartedAt`，并自行检测 `canRestart`。

本轮自动拉起实测在 macOS / Paper 26.3 build 202 完成，Windows 批处理仅为配置示例。

## English

Requires core.server.manage. Save first, then confirm Restart or choose Restart later; Restart to apply remains available. Configure Paper's settings.restart-script using the server's actual launch command, preserving the Java path, memory settings and any terrain javaagent. The module never writes a launch script or accepts shell commands from the browser.

Missing/unreadable scripts reject restart with HTTP 400 without stopping the server. Duplicate requests are rejected. The delayed task rechecks the script and pending changes before invoking Paper. Recovery is confirmed by a changed JVM start time, not by an HTTP success response. After 120 seconds without confirmation, the page asks the operator to check the server control panel and does not retry the mutation. The presence check cannot prove a script's contents are correct. This release's automatic-launch test used macOS; the Windows snippet is an example, not a tested deployment.
