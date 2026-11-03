# LunarCore 琉月

**v0.0.6 Test Preview** · Paper 26.3 build 202 · Java 25 · GPL v3

[简体中文](README.md) | [Download v0.0.6](https://github.com/X-LunarPale/LunarCore-LC/releases/tag/v0.0.6-preview) | [Install and upgrade](docs/en-US/INSTALL.md) | [GitHub module market](module-market/README.en.md) | [Contribute](CONTRIBUTING.en.md)

LunarCore 琉月 (LC) provides basic commands and a web panel for Minecraft survival servers. Install economy, land claims, chat, AI and other features separately.

## Install

1. Place `LunarCore-0.0.6-preview.jar` in the server's `plugins/` folder.
2. Start the server and wait for its libraries to download.
3. Open `http://127.0.0.1:8765` on the server computer. Follow the console login instructions and change the password.
4. Install features under **Modules → Module market**, then fully restart.

The core includes basic commands, the panel, accounts, database access and operation logs. Optional module JARs belong in `plugins/LunarCore/modules/`, not directly in `plugins/`.

Upgrading keeps existing modules, settings, databases and worlds. Stop and back up first; see [Install and upgrade](docs/en-US/INSTALL.md).

## Features

| Area | Features |
|---|---|
| Core | Web panel, players, console, accounts, roles, optional two-step verification |
| Basic commands | Flight, healing, items, workstations, time, weather and kits |
| Survival | Economy, daily rewards, land claims, votes, journal, building landmarks |
| Players | Teleportation, chat and multiple AI characters, titles, scoreboard |
| Protection | Punishments, anti-xray, GrimAC settings, game permissions, QQ authentication |
| Server | Worlds, terrain, performance, server-list text |

## Module Market

The [GitHub module market](module-market/README.en.md) provides descriptions and individual downloads. The panel supports installation, updates, removal and cancellation. It handles one set of changes at a time and downloads required modules together. Fully restart when **Restart required** appears. Removal keeps settings and data.

The public repository and module downloads need no token. Everyone can [submit community modules](CONTRIBUTING.en.md) through a PR or the Issues form. See [Modules](docs/en-US/MODULES.md).

## Guides

- [Panel, AI and QQ authentication](docs/en-US/USAGE.md)
- [FAQ and current limitations](docs/en-US/FAQ.md)
- [Release notes](docs/en-US/RELEASE.md)
- [Development and builds](DEVELOPMENT.en.md)
- [Documentation and historical records](docs/README.en.md)

This is a test preview. Third-party compatibility, terrain rendering and multiplayer behavior need real-server checks. A sudden crash may require manual reconciliation of economy and land records; see the FAQ.

## License

Project code uses **GPL-3.0-only (GPL v3)**. See [LICENSE](LICENSE) for the full text. Third-party credits and licenses are retained in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Releases include corresponding source.
