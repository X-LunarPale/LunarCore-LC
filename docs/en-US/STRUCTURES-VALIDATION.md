# Structure port validation

[Usage](STRUCTURES.md) | [中文](../zh-CN/STRUCTURES-VALIDATION.md)

Validated on 2026-11-04 against Java 25.0.3 and Paper 26.3 builds 202 and 238. Repository base: `a79ce3f8477db8e3ee25db563a611523218b78d3`. All runtime checks used isolated test worlds.

## Build and management

The repository Gradle suite and plugin builds pass: 454 recorded tests, 453 passed and one existing conditional skip, with no failures. All 98 frontend tests, Vite build and localization checks pass. All 2,148 files from the ten pinned upstream repositories match their source manifests.

Additional checks cover revisions, inheritance, failed atomic saves, preserved vanilla registry IDs, the actual 26.3 decorated-pot codec, template attributes and legacy setting fields. A read-only Web account received 403 for configuration/status endpoints; stale revisions were rejected. Browser checks exercised global and world saves, restored inheritance, restart status, Chinese/English and a 390px viewport.

## Live generation

Paper 238, seed `1234`: naturally locate each structure, generate its full footprint, then inspect native starts, blocks and block entities.

| Family | Pieces | Block entities |
| --- | ---: | ---: |
| Witch hut | 3 | 4 |
| Small dungeon | 5 | 9 |
| Oak mineshaft | 86 | 2 |
| Jungle temple | 42 | 67 |
| Stronghold | 918 | 385 |
| Desert temple | 460 | 211 |
| Ocean monument | 1,467 | 1 |
| Nether fortress | 661 | 66 |

All sampled blocks use the `minecraft:` namespace. Loot-bearing containers, spawners and witch-hut brewing contents were inspected. An unmodified official 26.3 client joined the local server and loaded structures. It used a local offline test account, without user account credentials.

On Paper 202 a witch hut survived saving, disabling its generation switch and restarting with identical structure/block-entity records. Dungeons and desert temples were also checked. A native Tectonic world with height 640, vertical scale 1.8 and horizontal scale 1.2 generated a 492-piece desert temple with 213 block entities. A fresh generation after the template fixes produced no Forge-attribute or decorated-pot decoding warnings.

The actual End fight controller completed both initial summoning and four-crystal resummoning in 605 accelerated stage updates, with exactly one dragon and ten vulnerable spike crystals without lingering beams. Restart preserved the dragon and kill counter. Gateway exit, return linkage and landing positions were checked. Accelerated stage execution is not a recording of a complete human-played fight.

### Extras and Bridges (Paper 26.3 build 238)

- Tested in new chunks of an existing overworld. With the switches on, a wishing well with `yungsextras:desert/extra_archeology`, swamp stone brick builds and river bridges generated. After switching off and restarting, a new swamp area had none of those stone brick builds.
- A bridge at the write limit triggered `unsafe terrain read`. After the fix, the same region was deleted and regenerated from the same seed: the bridge still generated, with no `unsafe terrain read` or `far chunk` errors.
- Console dialog after saving: the X and Cancel leave the server running; Confirm restart restarts through `start.sh` and the new settings apply.
- Not separately tested: these features in Tectonic worlds and custom dimensions, and the Confirm stop path without a `restart-script`.

## Vanilla comparison boundary

A same-seed Paper server without the structure plugin was compared with the plugin installed and every switch disabled. Desert, Nether and End 3×3 chunk samples contain **2,064,384** blocks: block hashes, biome hashes and structure starts matched exactly. The central witch-hut chunk also matched.

The surrounding swamp sample had small ore/stone overlap differences. Repeated unmodified Paper runs also reproduced that class of difference, including attempts with fixed requests and a single worker. [Paper #14125](https://github.com/PaperMC/Paper/issues/14125) documents generation-order-dependent decoration. The local result is consistent with that report, without claiming a proof for every differing block.

The original acceptance condition of universal, block-for-block identity for arbitrary fixed seeds is therefore **not marked fully passed**. This port does not alter Paper's decoration scheduler to impose a new generation order. With all switches off it retains the native noise generator and disables the nine custom structure families.

## Reproduction

See `DEVELOPMENT.en.md`. An already bootstrapped server can supply native dependencies through `-Plbr.paperServerHome=/absolute/path/to/paper-home`.

```sh
python3 tools/structures-probe/build.py /absolute/path/to/paper-home
```

The probe is console-only and restricted to `structures-*` test worlds; its binary is not shipped. `smoke` locates and checks natural structures; `fingerprint` hashes generated chunks; `end-cycle`, `end-resummon`, `end-status` and `gateway` inspect End behavior. `end-cycle` resets the test arena. These are acceptance commands, not production administration features.

Run `structuresprobe end-status minecraft:the_end` first to load the arena; without players, its entities may otherwise be unloaded. Portable generation counts and comparison hashes are in `integrations/lbr-structures/validation/`.
