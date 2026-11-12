# 地形启动支持

LunarCore 的 Tectonic 模块通过这个 Java 启动组件处理海底神殿高度、群系温度、
客户端雪线和新旧区块衔接。它只使用地形模块登记的世界设置。

使用 Java 25 构建：

```powershell
.\gradlew.bat :terrain-agent:jar
```

把 `LC-Terrain-Agent.jar` 放在 Paper 核心旁边，在平常的 Java 启动命令中加上：

```text
java -Xmx4G -javaagent:LC-Terrain-Agent.jar -jar paper.jar --nogui
```

Windows 也可使用仓库自带的启动脚本：

```powershell
.\tools\start-terrain-server.ps1 -PaperJar E:\server\paper.jar
```

安装或更新启动组件后，需要完整重启服务器。不要把它放进 `plugins` 当普通插件
加载。地形模块会检查启动支持是否就绪，不再按固定 Paper 构建号判断。

使用已启动过的本地 Paper 服务端运行测试：

```powershell
.\gradlew.bat :terrain-agent:test "-Plc.paperServerHome=E:/LC-Test-26.3"
```
