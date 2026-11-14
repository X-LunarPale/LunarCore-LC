# Multiverse World Panel

[简体中文](README.md)

Manage Multiverse worlds, aliases, PVP, weather and autoload in LunarCore. Save and unload empty worlds or teleport online players to safe spawn points.

## Install

Place `LcMultiverseWeb.jar` and the official Multiverse-Core plugin in `plugins/`, update LunarCore and restart. The bridge uses Multiverse 5's public API and does not include Multiverse itself.

The panel reports missing, disabled or incompatible Multiverse APIs. Reload the panel after Multiverse becomes available.

## Terrain Worlds

With the updated `lc-terrain.jar`, new and registered terrain worlds join Multiverse automatically. Manual `mv import` is no longer needed.

The Multiverse panel delegates terrain loading, unloading and autoload changes to LC so saved native generation settings are restored. MV's own autoload flag stays off. Existing world folders and dimension keys remain unchanged.

To permanently remove a terrain world, open **Tectonic terrain**, choose **Delete world** and enter the full name. Primary worlds, the MV default world and worlds containing online players cannot be unloaded or deleted.

## Permissions and API

API base: `/api/ext/multiverse`. Permissions belong to LunarCore panel accounts.

| Endpoint | Permission | Action |
| --- | --- | --- |
| `GET /status`, `GET /worlds` | `multiverse.view` | Status and world list |
| `POST /configure` | `multiverse.configure` | Alias, PVP, weather and autoload |
| `POST /lifecycle` | `multiverse.lifecycle` | Load or save and unload |
| `POST /teleport` | `multiverse.teleport` | Teleport an online player to a safe spawn |

Teleports use MV's safety checks and record their final result. MV handles ordinary worlds; LC initializes native terrain worlds.

## Build

Run with Java 25 from the repository root:

```text
gradlew :lc-multiverse-web:test :lc-multiverse-web:jar
```

Upstream: [Multiverse-Core](https://github.com/Multiverse/Multiverse-Core).
