# Use Modules

[简体中文](../zh-CN/MODULES.md) | [GitHub catalog](../../module-market/README.en.md)

A module is a feature set installed separately. The core includes `essentials` basic commands. Install other modules as needed.

## Market

1. Open **Modules → Module market** and search or filter by category.
2. Select **Install** or **Update**. Required modules download together.
3. Fully stop and start the server when **Restart required** appears.
4. Check the **Installed** tab.

The market handles one set of changes at a time. Cancel pending changes to choose again. It offers official modules matching the current core. See [Installation](INSTALL.md) for core upgrades.

On the Installed tab, wait for an enable, disable or reload operation to finish. Disabling requires confirmation; cancelling leaves the module unchanged. Failed reads offer Retry. If a refresh fails, the previous list remains visible and does not confirm the result of the latest operation.

## WebUI Effects

After installing and enabling `lc-webui-effects.jar`, open **Visual effects** in the panel header. **Liquid Glass** and **Full motion** are off by default. Each setting applies to the current browser. The extra animations are disabled when the system requests reduced motion.

## Removal

Removal deletes only the JAR, keeping settings, databases and worlds. Remove dependent modules first. Basic commands cannot be removed through the market.

## Public Downloads and Submissions

The v0.1.0-pre.2 source, module market and release assets are public in [LunarCore-LC](https://github.com/X-LunarPale/LunarCore-LC). Browser downloads and panel installation require neither a GitHub login nor a token.

Everyone can [submit modules](../../CONTRIBUTING.en.md) through a PR or Issues. Authors upload files to their own public Releases; the catalog links to those files. Community modules currently use manual installation. The panel installer offers official modules released with the core.

## Additional Setup

- Building landmarks requires land claims.
- Money rewards, paid land purchases and item sales require economy.
- Anti-cheat needs compatible GrimAC installed separately.
- AI needs a model service address, model name and key.
- Performance optimizations start disabled.
- QQ authentication needs a group and bot connection and does not start automatically.
- Terrain needs `LC-Terrain-Agent.jar` and a startup argument; see [Installation](INSTALL.md).
- The structure management module needs `LunarCoreStructures.jar` installed separately. The generation plugin is not included in the core; see [Structure upgrades](STRUCTURES.md).

After configuring QQ authentication, stop the server, set `modules.qqauth-autostart` to `true` and remove only `qqauth` from `modules.disabled`. Keep other disabled entries. Enabling or reloading may disconnect players.

## Standalone Plugins

`LcMultiverseWeb.jar` and `LcPlayerTitleWeb.jar` belong in `plugins/`, requiring Multiverse and PlayerTitle respectively. They are separate from internal modules and do not include the third-party plugins.
