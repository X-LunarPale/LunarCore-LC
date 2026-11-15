# Panel and Common Features

[简体中文](../zh-CN/USAGE.md) | [Home](../../README.en.md)

LC is short for LunarCore. Use `/lc help` for management commands and `/lc m list` to check module statuses as an administrator. Basic commands still work directly. Use `%lc_module_key%` in custom text, such as `%lc_core_online%`.

## Accounts

Assign panel access under **Accounts and roles**. Observers view basic status; moderators manage allowed features. Official LuckPerms handles in-game permissions separately. LC's permission panel connects directly to LP; see [Permissions](PERMISSIONS.md) for installation and common tasks.

Reset a panel password in the server console with `lc web user passwd account new-password`. Keep password-bearing logs private. If admin loses its authenticator and recovery codes, `lc web resetadmin` resets its password and clears its two-step verification.

## Two-step Verification

Enter your current password under **Profile** and start setup. Scan the QR code with an authenticator and confirm its current code. Future logins also require a code.

Keep the one-time recovery codes. A normal password change does not disable verification. Preserve the database and `web.jwt-secret` during upgrades; otherwise existing authenticator settings may need resetting.

## Branding and Language

Save or reset the name and logo separately under **Server settings → Branding**. Logos support PNG/JPEG, up to 256 KiB and 1024 pixels in each dimension. Branding appears publicly on the login page.

Switch Chinese and English on the login page or panel toolbar. Player input, configuration contents and console logs keep their original text.

## Maintenance Scheduling

Choose manual or scheduled control under **Server Display & Maintenance**. Manual mode follows the maintenance switch. Scheduled mode runs one maintenance window and reopens the server at the end. An empty end time keeps maintenance active. To end it early, switch to manual control and turn maintenance off.

Dates and times use the current device's time zone. A server restart restores the schedule without disconnecting players again. Automatic disconnection runs once when maintenance starts or when that option is enabled during maintenance. Whitelisted players are exempt.

Controls, whitelist, messages and server descriptions are edited and saved separately. Automatic status refresh preserves drafts. Manual disconnection uses the saved whitelist and message.

## Chat Appearance and Announcements

Set world display names and colors in chat settings without renaming English world folders. When matching world brackets are enabled, `{world}` already includes them; do not add another pair to the format.

Chat, TAB player names and overhead titles have separate controls. New configurations hide titles before player names in chat and TAB. Enable overhead titles separately and set their height and viewing distance. The built-in administrator format keeps the blue player name without an extra management label.

Choose an example world, player, title and message in the display preview. It sends nothing to the game; actual client rendering is authoritative. Existing custom formats are preserved. Upgrading does not replace every custom format or title-display choice.

Announcement switches, intervals and contents apply when saved. Reloading or disabling the chat module stops its previous announcement task to prevent duplicate broadcasts.

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

QQ verification is required by default. Turn off **Require QQ verification** to let unbound players register and sign in to the game. They receive one reminder per calendar day after signing in. Use `/qqauth bind` to get a code, then send `/验证 code` in the configured QQ group.

Unverified players cannot change or recover their password, delete their account, or remove a binding themselves. Daily reminders can be disabled. Dates use the configured time zone, or the server time zone when blank; reconnecting or reloading does not repeat the reminder.

Message edits apply immediately. Apply other settings with **Apply settings**, `qqauth reload`, or a full restart. Reloading revokes online authentication and disconnects players. See [Modules](MODULES.md) for activation.

## GrimAC, Performance and Worlds

The anti-cheat module provides translated GrimAC setting names. Explicitly reload GrimAC after saving; unsupported fields remain read-only.

Optimizations start disabled. Review them before enabling; operation counts do not prove improved performance. Terrain creates separate new worlds without converting existing ones. Fully restart after installation or supported height changes. Unloading worlds saves them without deleting their folders.

Under **World management → Configure**, set whether players can travel from each world to the Overworld, Nether and End separately. For example, disabling Nether travel still allows travel to the End. Portals and cross-dimension teleports follow these settings; travel within the same environment is unaffected. Changes apply when saved.

Terrain also needs `-javaagent:LC-Terrain-Agent.jar` in the launch command for climate, monument placement and old-chunk blending. See the [terrain guide](../../modules/lc-terrain/README.en.md) for installation, settings and `/tectonic` commands.

## NPC Random Teleport

Run `rtpnpc world player [radius]` as the console to send the player to a safe point in the target world around `(0,0)`. The default circular radius is 1000 blocks. The world must already be loaded. For NpcPlugin-Paper, set the click action to `rtpnpc world @s 1000` with the `CONSOLE` executor; `@s` represents the clicking player.

There is no warmup or charge. RTP cooldowns, land checks and `/back` recording still apply. Players do not need the `lc.teleport.rtp.npc` permission.
