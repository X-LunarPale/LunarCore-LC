# LunarCore 琉月 Module Market

[简体中文](README.md) | [Installation](../docs/en-US/MODULES.md) | [All downloads](https://github.com/X-LunarPale/LunarCore-LC/releases/tag/v0.0.8-pre.1)

Public downloads require no login or token. [Community modules](community/) · [Submit a module](../CONTRIBUTING.en.md) · [Submission form](https://github.com/X-LunarPale/LunarCore-LC/issues/new?template=module-submission.yml)

For **v0.0.8-pre.1**, Java 25 and Paper 26.3. The core includes basic commands; select optional modules below.

## Install

Use **Modules → Module market** in the panel. For manual installation, stop the server and put module JARs in `plugins/LunarCore/modules/`, then start. Do not place them directly in `plugins/`.

Replace older files instead of keeping duplicate JARs. Removal can keep settings and data. Install required dependencies together.

## Survival

| Module | Purpose | Download |
|---|---|---|
| Economy | Balances, transfers, rankings and optional Vault support | [lc-economy.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-economy.jar) |
| Daily rewards | Check-in rewards; money rewards need economy | [lc-signin.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-signin.jar) |
| Land claims | Claims, members and protection; paid purchases need economy | [lc-land.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-land.jar) |
| Gameplay votes | Vote on gameplay changes | [lc-vote.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-vote.jar) |
| Exploration journal | Record biomes, advancements and fishing | [lc-journal.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-journal.jar) |
| Building landmarks | Submit and review builds; requires land | [lc-landmarks.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-landmarks.jar) |

## Players

| Module | Purpose | Download |
|---|---|---|
| Teleportation | Homes, requests, warps and spawn | [lc-teleport.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-teleport.jar) |
| Chat and AI | Messages, mail, chat formats, multiple AI characters and memory | [lc-chat.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-chat.jar) |
| Titles | Create, grant, revoke and equip titles | [lc-titles.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-titles.jar) |
| Scoreboard | Sidebar and top information bar | [lc-scoreboard.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-scoreboard.jar) |

## Protection

| Module | Purpose | Download |
|---|---|---|
| Punishments | Bans, mutes, warnings and history | [lc-ban.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-ban.jar) |
| Anti-xray | Paper settings and mining alerts | [lc-antixray.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-antixray.jar) |
| Anti-cheat | GrimAC status and settings; install GrimAC separately | [lc-anticheat.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-anticheat.jar) |
| Game permissions | Groups, temporary permissions and checks | [lc-permissions.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-permissions.jar) |
| QQ authentication | Group and account authentication; starts disabled | [lc-qqauth.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-qqauth.jar) |

## Server

| Module | Purpose | Download |
|---|---|---|
| Worlds | Create, load, unload and configure worlds | [lc-world.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-world.jar) |
| Terrain | Separate worlds, climate and terrain search; requires the [startup agent](../modules/lc-terrain/README.en.md) | [lc-terrain.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-terrain.jar) |
| Performance | Metrics, cleanup and optional optimizations, initially disabled | [lc-performance.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-performance.jar) |
| Server list | Description text, colors and variables | [lc-motd.jar](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/lc-motd.jar) |

## Other Downloads

- [Core](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/LunarCore-0.0.8-pre.1.jar)
- [All optional modules](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/LunarCore-modules-0.0.8-pre.1.zip)
- [Corresponding source](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/LunarCore-0.0.8-pre.1-source.zip)
- [Checksums](https://github.com/X-LunarPale/LunarCore-LC/releases/download/v0.0.8-pre.1/SHA256SUMS.txt)

Multiverse and PlayerTitle management integrations are standalone plugins for `plugins/` and need their respective third-party plugins. See [Modules](../docs/en-US/MODULES.md).

## Publishing and Contributions

Everyone can publish a module: upload its JAR to your public Release, then add its entry under `community/` through a PR or use the Issues form. Provide purpose, supported versions, dependencies, license, source and download links. Run the included catalog checker before submitting.

The 19 official modules above support in-panel installation. Community modules use manual installation. Catalog entries are merged through PRs without a repository access token. Project code uses GPL v3 with third-party licenses retained.
