# Structure upgrades Web bridge

[中文](README.md)

This is an LC module, ID `structures`. Install `lc-structures-web.jar` in `plugins/LunarCore/modules/`. It contains no YUNG’s engine or data packs.

## Installation and upgrade

Stop the server. Install the compatible LunarCore core and the separate [rtiags/LunarCoreStructures](https://github.com/rtiags/LunarCoreStructures) generation plugin in `plugins/`, and this module in `plugins/LunarCore/modules/`. Fully restart. Open **Server → Structure upgrades** in Web.

Requires Java 25 / Paper 26.3, a core exposing `NativeGeneratorDelegate`, and generation management API v1. The older combined plugin lacks that API. Use the core from the bridge PR; the `0.0.6-preview` label alone does not establish compatibility.

Keep the existing `plugins/LunarCoreStructures/structures.json` when replacing the generation JAR. Do not keep duplicate plugins. Global defaults, world overrides and inheritance retain their meaning. Saving affects new chunks only after a full restart. Automatic restart requires a readable configured restart-script. Without it, the page only explains how to restart manually and the server keeps running. Confirm immediately after saving or use Restart to apply later. The page confirms recovery using the JVM start time and never repeats a timed-out restart request. See [restart setup](RESTART.md).

## Lifecycle and API

The module binds to the plugin on enable and handles plugin enable/disable events. A missing, disabled or incompatible plugin leaves a status page and rejects writes. Reload rebinds; disable removes routes/resources/listeners and cancels pending restart tasks. Disabling the bridge does not disable generation. The generation plugin must not be hot-unloaded.

All routes require `core.server.manage`: `GET /api/ext/structures/status`, `GET/POST /api/ext/structures/config`, `POST /api/ext/structures/restart`. Disconnected status returns `available:false/message`; config/restart return 503. Invalid input or stale revisions return 400, I/O failure returns 500 with a server log entry.

The bridge resolves public API v1 through the actual plugin ClassLoader and exchanges only JDK maps/lists/scalars. World queries run on the main thread. The generation plugin retains sole ownership of synchronized config storage, atomic saves and revision checks. Changes and restarts are audited by LC.

The self-contained Vue bundle is registered through `registerStatic/registerPanel`, without a CDN. It uses the existing same-origin login token, supports reverse-proxy path prefixes, language and theme changes, and stops writes after session expiry.

## Build and submission

Run `./gradlew :lc-structures-web:test :lc-structures-web:jar` at the repository root with JDK 25 and Node/npm. Gradle runs npm ci and Vite. Run `npm test` in the module's `web/` directory for frontend checks. Output: `build/libs/lc-structures-web.jar`.

This is a community extension, separate from the official 19-module catalog. After publishing, generate an entry using `tools/prepare-structures-market.py` and the actual JAR, version and public download URL, then run `python3 tools/validate-module-market.py`. External plugin requirements belong in the description, not LC-module `depends`.

See [validation](VALIDATION.md).
