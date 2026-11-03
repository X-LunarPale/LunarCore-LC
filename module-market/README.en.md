# LunarCore 琉月 Module Market

[简体中文](README.md) | [Installation](../docs/en-US/MODULES.md) | [All downloads](https://github.com/X-LunarPale/LunarCore-LC/releases/tag/v0.0.6-preview)

Public downloads require no login or token. [Community modules](community/) · [Submit a module](../CONTRIBUTING.en.md) · [Submission form](https://github.com/X-LunarPale/LunarCore-LC/issues/new?template=module-submission.yml)

For **v0.0.6 Test Preview**, Java 25 and Paper 26.3 build 202. The core includes basic commands; select optional modules below.

## Install

Use **Modules → Module market** in the panel. For manual installation, stop the server and put module JARs in `plugins/LunarCore/modules/`, then start. Do not place them directly in `plugins/`.

Replace older files instead of keeping duplicate JARs. Removal can keep settings and data. Install required dependencies together.

## Survival

| Module | Purpose | Download |
|---|---|---|
| Economy | Balances, transfers, rankings and optional Vault support | [lbr-economy.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-economy.jar) |
| Daily rewards | Check-in rewards; money rewards need economy | [lbr-signin.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-signin.jar) |
| Land claims | Claims, members and protection; paid purchases need economy | [lbr-land.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-land.jar) |
| Gameplay votes | Vote on gameplay changes | [lbr-vote.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-vote.jar) |
| Exploration journal | Record biomes, advancements and fishing | [lbr-journal.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-journal.jar) |
| Building landmarks | Submit and review builds; requires land | [lbr-landmarks.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-landmarks.jar) |

## Players

| Module | Purpose | Download |
|---|---|---|
| Teleportation | Homes, requests, warps and spawn | [lbr-teleport.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-teleport.jar) |
| Chat and AI | Messages, mail, chat formats, multiple AI characters and memory | [lbr-chat.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-chat.jar) |
| Titles | Create, grant, revoke and equip titles | [lbr-titles.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-titles.jar) |
| Scoreboard | Sidebar and top information bar | [lbr-scoreboard.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-scoreboard.jar) |

## Protection

| Module | Purpose | Download |
|---|---|---|
| Punishments | Bans, mutes, warnings and history | [lbr-ban.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-ban.jar) |
| Anti-xray | Paper settings and mining alerts | [lbr-antixray.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-antixray.jar) |
| Anti-cheat | GrimAC status and settings; install GrimAC separately | [lbr-anticheat.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-anticheat.jar) |
| Game permissions | Groups, temporary permissions and checks | [lbr-permissions.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-permissions.jar) |
| QQ authentication | Group and account authentication; starts disabled | [lbr-qqauth.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-qqauth.jar) |

## Server

| Module | Purpose | Download |
|---|---|---|
| Worlds | Create, load, unload and configure worlds | [lbr-world.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-world.jar) |
| Terrain | Mountains and underground rivers in separate new worlds | [lbr-terrain.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-terrain.jar) |
| Performance | Metrics, cleanup and optional optimizations, initially disabled | [lbr-performance.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-performance.jar) |
| Server list | Description text, colors and variables | [lbr-motd.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/lbr-motd.jar) |

## Other Downloads

- [Core](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/LunarCore-0.0.6-preview.jar)
- [All optional modules](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/LunarCore-modules-0.0.6-preview.zip)
- [Corresponding source](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/LunarCore-0.0.6-preview-source.zip)
- [Checksums](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.6-preview/SHA256SUMS.txt)

Multiverse and PlayerTitle management integrations are standalone plugins for `plugins/` and need their respective third-party plugins. See [Modules](../docs/en-US/MODULES.md).

## Publishing and Contributions

Everyone can publish a module: upload its JAR to your public Release, then add its entry under `community/` through a PR or use the Issues form. Provide purpose, supported versions, dependencies, license, source and download links. Run the included catalog checker before submitting.

The 19 official modules above support in-panel installation. Community modules use manual installation. Catalog entries are merged through PRs without a repository access token. Project code uses GPL v3 with third-party licenses retained.
