# LuckPerms Permissions

[简体中文](../zh-CN/PERMISSIONS.md) | [Home](../../README.en.md)

LC uses official LuckPerms for in-game permissions. The permission panel reads and updates LP directly instead of calculating permissions itself. Panel accounts and roles remain separate under **Accounts and roles**.

## Install

1. Download the Bukkit version from [LuckPerms](https://luckperms.net/download) and put it in the server's `plugins/` directory.
2. Install **LuckPerms bridge** from the LC module market. For manual installation, place `lc-permissions.jar` in `plugins/LunarCore/modules/`.
3. Fully restart the server and open **Permissions** in the LC panel.

On Windows, you can run this from the project directory:

```powershell
.\tools\install-luckperms.ps1 -ServerDirectory "D:\Minecraft"
```

The script downloads the latest official Bukkit release and replaces older official Bukkit JARs in the same directory. It does not start or restart the server. LuckPerms is a separate plugin, not an LC module.

When LP is missing or disabled, the panel shows its connection status and does not modify game permissions.

## Use

- Create groups and manage weights, inheritance and permissions.
- Set player groups and allow or deny permission nodes, with optional expiry.
- Find LC and other plugin nodes in the registered permission list.
- Check a player's effective permissions.

The panel and `/lp` use the same data. Existing LP groups and players do not need to be recreated.

LC command nodes start with `lc.`. For example, add a player to a group and allow flight:

```text
/lp user PlayerName parent add member
/lp group member permission set lc.essentials.fly true
```

Create a missing group with `/lp creategroup member` first. Find other nodes in the panel; `true` allows a permission and `false` denies it.

## Existing Data

On its first connection to LP, the bridge reads the old `plugins/LunarCore/modules/permissions/permissions.json`. Groups, players, inheritance and unexpired temporary grants are imported, and old LC nodes are renamed to `lc.*`. A successful import is recorded; the original file remains, while LP becomes the active store.

Do not reinstall the old permission engine. Manage subsequent changes in the panel or with `/lp`.
