# YUNG’s structure upgrades

[中文](../zh-CN/STRUCTURES.md) | [Installation](INSTALL.md) | [Validation](STRUCTURES-VALIDATION.md)

Use **Server → Server Operations → Structure upgrades** to select vanilla or YUNG’s generation for mineshafts, dungeons, strongholds, desert temples, jungle temples, witch huts, ocean monuments, Nether fortresses and the main End island. Clients need no mods or additional resource pack.

## Installation and settings

Stop the server and place matching `LunarCore-<version>.jar` and `LunarCoreStructures.jar` files in `plugins/`. The structures JAR is a standalone Paper plugin, not an internal module. Use Java 25 and Paper 26.3.

This local port build has not been published to the upstream module market. If using land, world management or native terrain, also update their JARs from this bundle's `modules/` directory while stopped, so the interface implementations match this build.

All switches default to off. Select server defaults or a world; worlds inherit by default and can have independent settings. Re-enabling inheritance removes that world's overrides. Save and fully restart to apply changes. Saving alone does not change running generation. Structures already started or built are retained; converted End dimensions keep the gameplay support needed by their arena and dragon fight.

The scope is the nine Better structure projects. Bridges, Extras, cave overhauls and optional integrations requiring mods such as Create are excluded. Upstream defaults are retained, including the disabled small Nether dungeon option.

## Existing structures

Replacement defaults to off. Startup maintenance scans saved regions and replays vanilla and replacement generation in isolated temporary dimensions. The comparison includes the expanded footprint and containers.

Player edits, unmatched terrain or inventories, uncertain entities, claims, unavailable protection data, missing chunks and operations exceeding the safety limit are skipped with a reason. Only cactus and sugar-cane growth timers are normalized; added, missing or different blocks remain conflicts. Each operation is limited to 256 chunks.

Old monster rooms require compatible spawner, room and replay evidence. Existing End islands are preserved when their complete fight history cannot be verified. This is not a forced End-reset tool.

Affected worlds cannot be entered during maintenance and new logins are deferred. Cancellation waits for the current structure to finish or roll back; recovery is mandatory. Completed or cancelled revisions are not rescanned on every restart. Changing and saving settings schedules another check.

## Backup and recovery

Settings are in `plugins/LunarCoreStructures/structures.json`. Each operation under `plugins/LunarCoreStructures/structure-backups/<operation-id>/` contains before/after chunks, entity data, checksums, bounds and its journal.

No gameplay-world write begins before backup succeeds. Interrupted operations roll back at the next startup even if replacement has since been disabled. If recovery is required, retain the operation directory, restore missing or damaged backup files, and restart. Do not delete the journal or mark it committed to bypass recovery.

Disabling generation preserves existing buildings. Keep the plugin installed for saves containing its structure data. Hot unloading and `/reload` generation changes are unsupported. Include worlds, LunarCore data and structures plugin data in routine full backups.

## Permissions and compatibility

Reading, saving and cancellation use `core.server.manage`. Saves are audited and stale revisions rejected.

The adapter targets Paper 26.3 native noise worlds, including LunarCore Tectonic worlds. Unknown generators or generation histories are conservatively skipped. Folia, Fabric and NeoForge are not runtime targets. See the validation record for tested builds and verification limits.
