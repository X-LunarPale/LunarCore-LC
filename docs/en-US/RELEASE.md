# v0.0.7

[简体中文](../zh-CN/RELEASE.md) | [Home](../../README.en.md)

## Changes

- Completes Tectonic server terrain features: per-world climate, snowlines, monument height, old-chunk blending, custom height, terrain information and eight locate targets. Install the startup agent and fully restart.
- Players can disable returning to the hub after death with `/spawn ondeath off` and restore it with `/spawn ondeath on`. A clickable hint appears after returning, and the choice is saved.
- The first login each day uses the configured hub. Later logins that day keep the logout position. The day boundary uses a configurable timezone.
- Fixes the QQAuth AstrBot plugin's default port, adds OneBot/NapCat group messages and explains connection errors.
- Adds global and per-world structure management. Install the generation plugin `LunarCoreStructures.jar` separately; it is not included in the core.
- The core still includes only basic commands, with 19 official optional modules. Existing settings, databases and worlds are kept.

## Attachments

| File | Purpose |
|---|---|
| `LunarCore-0.0.7.jar` | Core for `plugins/` |
| `lc-*.jar` | Optional modules for `plugins/LunarCore/modules/` |
| `LunarCore-modules-0.0.7.zip` | Official optional modules and the structure management module |
| `LunarCore-0.0.7-source.zip` | Source and build files |
| `LunarCore-0.0.7.zip` | Core, modules, source and installation guide |
| `LC-Terrain-Agent.jar` | Terrain startup agent, placed in the server directory with a startup argument |
| `lc-structures-web.jar` | Structure management; download the generation plugin separately |
| `LcMultiverseWeb.jar`, `LcPlayerTitleWeb.jar` | Optional standalone plugins |
| `module-market.json` | Versions, dependencies and file checksums |
| `SHA256SUMS.txt` | Download checksums |

Add `-javaagent:LC-Terrain-Agent.jar` before `-jar` in the Java command. Fully restart after updating the agent.

See [Installation](INSTALL.md) for upgrading, [Validation](VALIDATION.md) for test records and [Structure upgrades](STRUCTURES.md) for the generation scope.
