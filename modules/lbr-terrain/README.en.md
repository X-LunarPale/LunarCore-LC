# Tectonic Terrain

[简体中文](README.md)

Ports Tectonic 3.0.29 server terrain features to Paper 26.3. Open **Server → Tectonic terrain** to create separate worlds with configurable mountains, continents, oceans, caves, underground rivers, climate and height.

No Fabric, NeoForge or datapack is required. The primary overworld keeps its generation settings.

## Install

Use Java 25 and Paper 26.3. Native interfaces are checked instead of requiring a fixed Paper build number.

1. Update the LunarCore core and place `lbr-terrain.jar` in `plugins/LunarCore/modules/`.
2. Put `LC-Terrain-Agent.jar` beside the Paper JAR.
3. Add `-javaagent:LC-Terrain-Agent.jar` to your existing launch command and fully restart.

```text
java -Xmx4G -javaagent:LC-Terrain-Agent.jar -jar paper.jar --nogui
```

The startup agent handles temperatures, client snowlines, monument placement and old-chunk blending. It is not a Bukkit plugin. The panel reports a missing agent instead of advertising unavailable features. Restart after updating the agent.

## Settings

Eight presets include Classic, Monumental and Extreme Mountains, plus the official Default, Large Biomes, Desolate, Frozen Wasteland and Overkill settings. Base controls cover height, horizontal and vertical scales, rolling hills, underground rivers, lava tunnels and jungle pillars.

Default height ranges:

| Total height | Usable block Y range |
| --- | --- |
| 384 | -64 to 319 |
| 512 | -64 to 447 |
| 640 | -64 to 575 |
| 704 | -64 to 639 |
| 832 | -64 to 767 |
| 1024 | -64 to 959 |

Register custom bounds in the panel, then restart to make them available. Both bounds must be multiples of 16. The minimum is -2032 to 0, the exclusive upper bound is 256 to 2032, and total height is 256 to 4064. A minimum of -128 and upper bound of 896 gives a total height of 1024.

Height is available generation space, not a promised peak height. Horizontal scale accepts 0.5–3.0; vertical scale accepts 0.1–15.0.

There are 34 advanced settings:

| Group | Settings |
| --- | --- |
| Global terrain | Snowline, minimum height, elevation boost, ultrasmooth terrain |
| Continents and rivers | Ocean offset, continent/erosion/ridge scales, flatness, river lights and ice |
| Islands | Enabled, noise scale, multiplier, offset |
| Oceans | Ocean and deep-ocean depth, frozen-ocean ice removal, monument offset |
| Biomes | Temperature and vegetation scales, multipliers, offsets |
| Caves | Depth cutoff, cheese/noodle caves, carvers, ore distribution |
| Experimental | Alternate erosion scaling |

Official presets use snow offset 128, or 512 for Overkill, and monument offset -30. Existing saved configurations that omit these fields retain zero to preserve their settings.

Climate and generation settings apply per world. Snow offsets affect server temperature queries and client snowlines. The ice-free frozen ocean retains structure eligibility and biome tags. Monument offsets survive saving and loading. The lava floor follows the configured world bottom.

## Worlds and Saves

Names use 1–32 lowercase letters, digits, underscores or hyphens, beginning with a letter. Seeds support full 64-bit integers; leave blank for a random seed.

Load or save and unload worlds, set safe spawn points and teleport online players from the panel. Worlds containing players cannot unload. Unloading does not delete saves. Generation settings remain fixed after creation.

- Registrations: `plugins/LunarCore/modules/terrain/worlds.yml`.
- Worlds: `dimensions/lbr_terrain/<name>/` inside the primary save.
- Generation settings: that world's `data/minecraft/world_gen_settings.dat`.
- `config.yml` contains `auto-load`; manually unloaded worlds retain their own disabled autoload setting.
- Existing directories are not overwritten. Automatic conversion of existing worlds is not offered.
- Terrain worlds need the module's registered dimension types at startup. Do not remove the module and continue loading them.

The agent handles blending versions, chunk transitions and height-change compatibility. The panel still keeps registered world settings fixed.

## Commands and Permissions

Use `/tectonic debug` for local terrain information and `/tectonic locate <target>` for eight terrain types. Console users can append a world name. See [Commands](COMMANDS.md).

| Permission | Access |
| --- | --- |
| `terrain.view` | Panel status |
| `terrain.manage` | Panel world management, spawn and height registration |
| `terrain.teleport` | Panel teleport requests |
| `lbr.terrain.command` | In-game terrain information and search |

Game command permissions default to operators. Searches sample in the background without generating chunks. Results suggest a teleport command without automatically teleporting.

## API

Base path: `/api/ext/terrain`.

- `GET /status`: defaults, schema, presets, `heights`, `heightRanges` and world status.
- `POST /height-ranges`: `{min-y, max-y}`; restart to apply.
- `POST /worlds`: `{name, seed, config}`; returns 202 before generation finishes.
- `POST /worlds/{name}/load`, `POST /worlds/{name}/unload`.
- `POST /worlds/{name}/spawn`: integer `{x, y, z}` at a safe location.
- `POST /worlds/{name}/teleport`: `{player}`.

Send seeds as decimal strings to preserve precision. `config.advanced` uses upstream paths such as `"oceans.monument_offset": -30`; omitted fields use defaults. Spawn requires solid ground, two clear blocks and no liquids or hazards.

## Source and Builds

The source artifact is the official `tectonic-3.0.29-fabric-26.3.jar`. MIT attribution and extraction scripts are in `src/main/resources/upstream/`. Vanilla resources come from Paper; no mod loader or Minecraft assets are distributed.

Implementations cover terrain density, slopes, river decorations, ore overrides, carvers, temperature, monument offsets, old-chunk compatibility and commands. The pinned release's ineffective `spaghetti_enabled` and `alternate_continents_scaling` switches are not exposed as working options.

WebUI replaces the mod's client configuration screen. Block-for-block identity with the mod is not promised. Larger heights increase generation and memory costs; choose settings appropriate to the server.

```powershell
.\gradlew.bat :terrain-agent:test :lbr-terrain:test :terrain-agent:jar :lbr-terrain:jar "-Plbr.paperServerHome=E:/LBR-Test-26.3"
```

Omit the local Paper property to use the configured development bundle. Tests cover native serialization, height bounds, climate isolation, monument reloads, blending data and command sampling. `tools/terrain-native-probe/` is a separate test fixture.
