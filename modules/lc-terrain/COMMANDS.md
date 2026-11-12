# Terrain Commands

`/tectonic` requires `lc.terrain.command`, which is available to operators by default.
The commands query loaded LunarCore terrain worlds.

| Command | Result |
| --- | --- |
| `/tectonic debug [world]` | Shows the terrain region, erosion, region selector and cave depth cutoff at your position |
| `/tectonic locate <terrain> [world]` | Finds a matching terrain region near your position |

Players can omit `world` to use their current world. The console must supply a
loaded terrain world name or identifier such as `lc_terrain:alpine`. When another
world is supplied, the search starts at that world's spawn.

| Terrain | Description |
| --- | --- |
| `mountain_range` | Mountain range |
| `underground_river` | Underground river region |
| `jungle_pillars` | Jungle pillar region |
| `rolling_hills` | Rolling hill region |
| `badlands_canyon` | Badlands canyon region |
| `badlands_plateaus` | Badlands plateau region |
| `desert_dunes` | Desert dune region |
| `cherry_valley` | Cherry valley region |

Locators use the official Tectonic 3.0.29 conditions: inclusive density ranges,
values rounded to three decimal places, 64-block sample spacing and a 400-step
square spiral. Searches run asynchronously and do not generate or load chunks.
Features disabled for that world cannot be located.

Coordinates identify a terrain pattern, so the biome at the exact coordinate
may have a different name. The result suggests a teleport command; it does not
teleport anyone automatically.

Old terrain chunks without the current generation marker retain their original
section range for native blending. Their height maps and lighting are recalculated
when loaded. The marker and compatibility handling apply only to LunarCore terrain
worlds and leave the primary world untouched.
