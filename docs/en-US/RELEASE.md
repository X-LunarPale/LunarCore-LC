# v0.1.0 Pre-release (0.1.0-pre.1)

[简体中文](../zh-CN/RELEASE.md) | [Home](../../README.en.md)

## Changes

- The dashboard displays several trend charts together. The module list shows versions, statuses and details again. The console shows how many logs match the filter, and the server page links to management tools.
- The WebUI uses shadows to mark selections, with softer corners and updated spring animations.
- Delegated operators can run only allowed commands in the Web console. Panel administrators can adjust `commands.web-allow` and `commands.web-deny` in `config.yml`. Denied commands take priority.
- NPCs can send players to a random spot in a specified world. The update fixes safe landing searches for `/back` and allows per-world `/rtp` ranges.
- Fixes terrain world loading and Multiverse integration. Nether and End access can be restricted per world.
- Adds scheduled maintenance settings and fixes display issues in chat, the player list and the scoreboard.

## Upgrade

Stop the server, replace the core and any modules being updated with matching versions, and keep existing settings, databases and worlds. Fully start the server and check Modules.

Terrain still needs `-javaagent:LC-Terrain-Agent.jar` before `-jar` in the Java command. Fully restart after updating the agent.

## Attachments

| File | Purpose |
|---|---|
| `LunarCore-0.1.0-pre.1.jar` | Core for `plugins/` |
| `lc-*.jar` | Optional modules for `plugins/LunarCore/modules/` |
| `LunarCore-modules-0.1.0-pre.1.zip` | Official optional modules and the structure management module |
| `LunarCore-0.1.0-pre.1-source.zip` | Source and build files |
| `LunarCore-0.1.0-pre.1.zip` | Core, modules, source and installation guide |
| `LC-Terrain-Agent.jar` | Terrain startup agent, placed in the server directory |
| `lc-structures-web.jar` | Structure management; download the generation plugin separately |
| `LcMultiverseWeb.jar`, `LcPlayerTitleWeb.jar` | Optional standalone plugins |
| `module-market.json` | Module versions, dependencies and downloads |
| `SHA256SUMS.txt` | Download checksums |

See [Installation](INSTALL.md) for upgrade steps and [Modules](MODULES.md) for optional features.
