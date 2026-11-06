# YUNG’s structure upgrades

[中文](../zh-CN/STRUCTURES.md) | [Installation](INSTALL.md) | [Validation](STRUCTURES-VALIDATION.md)

Use **Server → Server Operations → Structure upgrades** to select vanilla or YUNG’s generation for mineshafts, dungeons, strongholds, desert temples, jungle temples, witch huts, ocean monuments, Nether fortresses, the main End island, desert and swamp decorations (YUNG’s Extras) and river bridges (YUNG’s Bridges). Clients need no mods or additional resource pack.

## Installation and settings

Stop the server and place matching `LunarCore-<version>.jar` and `LunarCoreStructures.jar` files in `plugins/`. The structures JAR is a standalone Paper plugin, not an internal module. Use Java 25 and Paper 26.3.

This local port build has not been published to the upstream module market. If using native terrain, also update its JAR from this bundle's `modules/` directory while stopped, so the interface implementations match this build.

All switches default to off. Expand Settings scope and tick server defaults, or one or more worlds to change together. Worlds inherit by default and can have independent settings. Re-enabling inheritance removes that world's overrides. Save to store changes. When the settings changed, a dialog explains that a restart is needed: the red Confirm restart button restarts now, while Cancel or closing the dialog leaves the server running, and any later full restart applies them. Saving alone does not change running generation. The console restart uses `restart-script` from `spigot.yml`, by default `start.sh` in the server directory. Without that script the button becomes Confirm stop, and the server only stops until you start it manually. Structures already started or built are retained; converted End dimensions keep the gameplay support needed by their arena and dragon fight.

The scope is the nine Better structure projects plus Extras and Bridges. Cave overhauls and optional integrations requiring mods such as Create are excluded. Upstream defaults are retained, including the disabled small Nether dungeon option.

## What changes

Switches affect only **newly generated chunks** and **new worlds**. Generated chunks are never altered and existing structures stay as they are. To see the new structures, explore ungenerated areas or create a new world.

Worlds from world management and native terrain worlds are supported, and new worlds inherit the server defaults. A structure next to generated terrain only appears in the new chunks, so it can be cut off at that boundary.

Better End Island applies only while the main island has not been generated; otherwise the End stays vanilla.

Desert and swamp decorations and river bridges are features, not structures: `/locate` cannot find them and the world keeps no structure record for them. With decorations on, new chunks replace the vanilla desert well with YUNG’s wells. River bridges appear only in river biomes with solid land on both banks. Positions beyond the write range are skipped, so bridges are never cut off. Tectonic worlds have fewer gentle banks, so bridges are clearly rarer there.

## Data and removal

Settings are in `plugins/LunarCoreStructures/structures.json`.

Disabling generation preserves existing buildings. Keep the plugin installed for saves containing its structure data. Hot unloading and `/reload` generation changes are unsupported. Include worlds, LunarCore data and structures plugin data in routine full backups.

## Permissions and compatibility

Reading and saving use `core.server.manage`. Saves are audited and stale revisions rejected.

The adapter targets Paper 26.3 native noise worlds, including LunarCore Tectonic worlds. Flat and plugin-generated worlds ignore structure settings and keep their existing generation. They cannot be ticked in Settings scope; hover the ⓘ next to the name for details. Folia, Fabric and NeoForge are not runtime targets. See the validation record for tested builds and verification limits.
