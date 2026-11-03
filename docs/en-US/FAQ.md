# FAQ

[简体中文](../zh-CN/FAQ.md) | [Home](../../README.en.md)

## Why Only Basic Commands?

Optional features are separate. Install them through the market, then restart. Existing modules are kept.

## Download Failed?

Check the network and matching Release assets. The public market needs no token. Retry or install matching JARs manually while stopped. Failed downloads do not replace installed files.

## Why Restart?

Changes are staged first. A full restart replaces or removes JARs. Reloading settings or toggling modules cannot apply them. Apply or cancel each set before starting another.

## Does Removal Delete Data?

Only the module JAR is removed. Settings, databases and worlds remain. Remove dependent modules first.

## Startup Cannot Apply Changes?

Read the console and keep the server stopped. Preserve original modules and data. `.market/backup/` holds files from before replacement; interrupted changes are restored before retrying. Fix the reported problem and retry. To abandon changes, back up and move `.market/pending.json` while stopped, keeping other files for diagnosis.

## Why Is the Old Name Shown?

Custom names are preserved. Reset Branding to use “LunarCore 琉月”. The data directory remains `plugins/LunarCore/`.

## Complete EssentialsX or LuckPerms Replacement?

Full compatibility is not guaranteed. Test specific commands and third-party behavior on a test server.

## Current Limitations

- Economy and land save separately. Crashes or database failures may leave payments and claims inconsistent, requiring manual reconciliation.
- Daily rewards prevent duplicates during normal operation, but crashes can interrupt rewards and recording.
- Terrain may differ from the original mod for the same seed. Rendering, chunk transitions and client experience need testing.
- Third-party plugins, MySQL, Linux and multiplayer load need environment-specific checks. No player capacity or performance gain is promised.
- The market manages official modules matching the current core. Upgrade the core manually.

## Report a Problem

Include plugin, Java and Paper versions, reproduction steps and relevant errors. Remove passwords, tokens, AI keys and private player information.
