# Terrain Startup Hooks

LunarCore's Tectonic module uses this small Java agent for ocean monument
placement, biome temperature, client snowlines and old-chunk blending.
It only applies world settings registered by the terrain module.

Build with Java 25:

```powershell
.\gradlew.bat :terrain-agent:jar
```

Put `LC-Terrain-Agent.jar` beside the Paper JAR, then add the agent to the
server's normal Java command:

```text
java -Xmx4G -javaagent:LC-Terrain-Agent.jar -jar paper.jar --nogui
```

On Windows, the repository also includes a launcher:

```powershell
.\tools\start-terrain-server.ps1 -PaperJar E:\server\paper.jar
```

The terrain module requires these hooks at startup. Restart the server after
adding or updating the agent. Do not install the agent as a Bukkit plugin.
The agent checks native method layouts rather than a fixed Paper build number.

To run the agent's native tests against a bootstrapped local Paper server:

```powershell
.\gradlew.bat :terrain-agent:test "-Plc.paperServerHome=E:/LC-Test-26.3"
```
