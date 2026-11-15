# v0.0.9 Pre-release (0.0.9-pre.1)

[简体中文](../zh-CN/RELEASE.md) | [Home](../../README.en.md)

## Changes

- WebUI actions pause related refreshes and prevent duplicate submissions. Failed module reads offer retry and retain the previous list. A menu refresh failure after a successful action is reported separately.
- Hidden pages and unopened modules make fewer periodic requests. Table searches use submitted text so background refreshes do not apply an unfinished query.
- Announcement switches, intervals and contents apply when saved. Reloading and disabling release previous announcement and display tasks to prevent duplicate work.
- Chat, TAB player names and overhead titles have separate settings, with world-label, color and title previews. World brackets can use the world color. Overhead titles support height and viewing-distance controls.
- Existing custom chat formats and display choices are preserved; only older built-in templates are updated. The built-in administrator format removes the management label and keeps the blue player name.
- Module statuses and player messages distinguish disabled features, unchanged values, work in progress and actual failures.
- Fixes QQAuth account recovery after an identity change on an offline-mode server.

## Upgrade

Stop the server, replace the core and any modules being updated with matching versions, and keep existing settings, databases and worlds. Fully start the server and check Modules.

Chat settings are grouped into filtering, announcements, chat appearance, overhead titles and the player list. Custom formats are kept. New configurations hide titles before player names in chat and TAB; overhead titles are enabled separately. If PlayerTitle and LC show the same title twice, disable the corresponding display in one provider. See [Usage](USAGE.md#chat-appearance-and-announcements).

Terrain still needs `-javaagent:LC-Terrain-Agent.jar` before `-jar` in the Java command. Fully restart after updating the agent.

## Attachments

| File | Purpose |
|---|---|
| `LunarCore-0.0.9-pre.1.jar` | Core for `plugins/` |
| `lc-*.jar` | Optional modules for `plugins/LunarCore/modules/` |
| `LunarCore-modules-0.0.9-pre.1.zip` | Official optional modules and the structure management module |
| `LunarCore-0.0.9-pre.1-source.zip` | Source and build files |
| `LunarCore-0.0.9-pre.1.zip` | Core, modules, source and installation guide |
| `LC-Terrain-Agent.jar` | Terrain startup agent, placed in the server directory |
| `lc-structures-web.jar` | Structure management; download the generation plugin separately |
| `LcMultiverseWeb.jar`, `LcPlayerTitleWeb.jar` | Optional standalone plugins |
| `module-market.json` | Module versions, dependencies and downloads |
| `SHA256SUMS.txt` | Download checksums |

## Validation Scope

All 119 frontend tests and the production build passed. The latest chat-module Java run passed all 60 tests. An earlier full Java test run had no failures; it does not replace a new full run after later changes.

Browser checks used simulated APIs for Chinese and English desktop and mobile layouts, error retry, operation locks and chat previews. They did not write to a real server and do not establish successful target-server deployment or actual client rendering. See [Validation](VALIDATION.md).

See [Installation](INSTALL.md) for upgrade steps and [Modules](MODULES.md) for optional features.
