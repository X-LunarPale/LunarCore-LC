# Panel and Common Features

[简体中文](../zh-CN/USAGE.md) | [Home](../../README.en.md)

LC is short for LunarCore. Use `/lc help` in game to see management commands; basic commands still work directly. Use `%lc_module_key%` in custom text, such as `%lc_core_online%`.

## Accounts

Assign panel access under **Accounts and roles**. Observers view basic status; moderators manage allowed features. In-game permissions are separate and handled by the permission module or another plugin.

Reset a panel password in the server console with `lc web user passwd account new-password`. Keep password-bearing logs private. If admin loses its authenticator and recovery codes, `lc web resetadmin` resets its password and clears its two-step verification.

## Two-step Verification

Enter your current password under **Profile** and start setup. Scan the QR code with an authenticator and confirm its current code. Future logins also require a code.

Keep the one-time recovery codes. A normal password change does not disable verification. Preserve the database and `web.jwt-secret` during upgrades; otherwise existing authenticator settings may need resetting.

## Branding and Language

Save or reset the name and logo separately under **Server settings → Branding**. Logos support PNG/JPEG, up to 256 KiB and 1024 pixels in each dimension. Branding appears publicly on the login page.

Switch Chinese and English on the login page or panel toolbar. Player input, configuration contents and console logs keep their original text.

## In-game AI

Install the chat module and open AI settings:

1. Enter the model service address (Base URL), API key and model name.
2. Set the AI name, personality and chat format, then save.
3. Choose mention-only replies or replies at a chosen probability.
4. Add more characters, a fallback model or memory as needed.

A fallback model is tried when the main request fails. An embedding model converts text into numbers to find related older conversations; configure its service separately.

Fetch provider model lists or query DeepSeek and supported sub2API balances. Availability depends on the provider. Automatic checking reads balances periodically.

Choose a custom error reply or silence. External AI calls may cost money and send relevant chat content to the selected provider.

## QQ Authentication

Edit group, connection, binding limits and messages. Previews use examples without sending messages to QQ or players.

Reload QQ authentication or fully restart to apply saved changes. Reloading revokes online authentication and disconnects players. See [Modules](MODULES.md) for activation.

## GrimAC, Performance and Worlds

The anti-cheat module provides translated GrimAC setting names. Explicitly reload GrimAC after saving; unsupported fields remain read-only.

Optimizations start disabled. Review them before enabling; operation counts do not prove improved performance. Terrain creates separate new worlds without converting existing ones. Fully restart after installation or supported height changes. Unloading worlds saves them without deleting their folders.

Terrain also needs `-javaagent:LC-Terrain-Agent.jar` in the launch command for climate, monument placement and old-chunk blending. See the [terrain guide](../../modules/lbr-terrain/README.en.md) for installation, settings and `/tectonic` commands.
