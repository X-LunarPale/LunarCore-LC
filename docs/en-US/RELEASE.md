# v0.0.8 Pre-release (0.0.8-pre.1)

[简体中文](../zh-CN/RELEASE.md) | [Home](../../README.en.md)

## Changes

- Set Chinese world labels and colors with a live preview without renaming English world folders. The default chat order is world, title, then player.
- Chat and TAB share the equipped PlayerTitle title, using configurable `%playerTitle_use%` by default. Empty titles remain hidden.
- Teleport requests and results, warnings, mutes and daily rewards have configurable notification sounds.
- Terrain worlds register with Multiverse automatically, including existing managed worlds. The MV bridge loads them through the terrain module to preserve generation settings.
- Terrain world deletion requires the exact world name. Main worlds, the Multiverse default world and occupied worlds cannot be deleted.
- Includes a GrimAC survival preset for Paper 26.3: simulation is disabled, setbacks are more tolerant, and version-sensitive checks only log and alert. Repeated violations can still trigger account punishments.
- Official LuckPerms remains the permission provider. The core includes basic commands, with other modules installed separately.

## Attachments

| File | Purpose |
|---|---|
| `LunarCore-0.0.8-pre.1.jar` | Core for `plugins/` |
| `lc-*.jar` | Optional modules for `plugins/LunarCore/modules/` |
| `LunarCore-modules-0.0.8-pre.1.zip` | Official optional modules and the structure management module |
| `LunarCore-0.0.8-pre.1-source.zip` | Source and build files |
| `LunarCore-0.0.8-pre.1.zip` | Core, modules, source and installation guide |
| `LC-Terrain-Agent.jar` | Terrain startup agent, placed in the server directory with a startup argument |
| `lc-structures-web.jar` | Structure management; download the generation plugin separately |
| `LcMultiverseWeb.jar`, `LcPlayerTitleWeb.jar` | Optional standalone plugins |
| `module-market.json` | Versions, dependencies and file checksums |
| `SHA256SUMS.txt` | Download checksums |

Add `-javaagent:LC-Terrain-Agent.jar` before `-jar` in the Java command. Fully restart after updating the agent.

See [Installation](INSTALL.md) for upgrading, [Validation](VALIDATION.md) for test records and [Structure upgrades](STRUCTURES.md) for the generation scope.

When LC manages chat and TAB, disable PlayerTitle's `isChat`, `isTabPrefix` and `isTabSuffix` to avoid duplicate titles. World labels, colors and titles are configured under Chat and social in the panel.

The GrimAC preset is at `modules/lc-anticheat/src/main/resources/presets/paper-26.3.yml`. Merge its main settings and punishment groups into the corresponding GrimAC files, and set `grim.exempt.simulation=true` on the LuckPerms default group. Do not replace an entire GrimAC file with the preset.
