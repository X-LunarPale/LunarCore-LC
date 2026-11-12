# LuckPerms Bridge

[中文](README.md)

Manage official LuckPerms users, groups, inheritance, weights and temporary grants from the LC panel. LuckPerms calculates and stores game permissions.

1. Place the official LuckPerms Bukkit JAR in `plugins/`.
2. Place `lc-permissions.jar` in `plugins/LunarCore/modules/`.
3. Fully restart the server and open Permissions in the panel.

Panel accounts need `permissions.view` to view or `permissions.manage` to edit. Panel permissions remain separate from game permissions.

Use the official `/lp` command in game. LC nodes start with `lc.`. The bridge imports the former `permissions.json` once, including denied nodes and active temporary grants.

See [permission setup](../../docs/en-US/PERMISSIONS.md) for details.
