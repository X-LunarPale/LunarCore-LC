# Structure port validation

[Usage and recovery](STRUCTURES.md) | [中文](../zh-CN/STRUCTURES-VALIDATION.md)

Validated on 2026-11-04 against Java 25.0.3 and Paper 26.3 builds 202 and 238. Repository base: `a79ce3f8477db8e3ee25db563a611523218b78d3`. All runtime checks used isolated test worlds.

## Build and management

The repository Gradle suite and plugin builds pass: 458 recorded tests, 457 passed and one existing conditional skip, with no failures. All 92 frontend tests, Vite build and localization checks pass. All 2,148 files from the ten pinned upstream repositories match their source manifests.

Additional checks cover revisions, inheritance, failed atomic saves, preserved vanilla registry IDs, the actual 26.3 decorated-pot codec, template attributes, intersecting claims, corrupt backup checksums and failed journal writes. A read-only Web account received 403 for configuration/status endpoints; stale revisions were rejected. Browser checks exercised global and world saves, restored inheritance, restart status, Chinese/English and a 390px viewport.

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

## Vanilla comparison boundary

A same-seed Paper server without the structure plugin was compared with the plugin installed and every switch disabled. Desert, Nether and End 3×3 chunk samples contain **2,064,384** blocks: block hashes, biome hashes and structure starts matched exactly. The central witch-hut chunk also matched.

The surrounding swamp sample had small ore/stone overlap differences. Repeated unmodified Paper runs also reproduced that class of difference, including attempts with fixed requests and a single worker. [Paper #14125](https://github.com/PaperMC/Paper/issues/14125) documents generation-order-dependent decoration. The local result is consistent with that report, without claiming a proof for every differing block.

The original acceptance condition of universal, block-for-block identity for arbitrary fixed seeds is therefore **not marked fully passed**. This port does not alter Paper's decoration scheduler to impose a new generation order. With all switches off it retains the native noise generator and disables the nine custom structure families. Conservative retrofits still reject replay differences rather than relaxing block conflict checks.

## Retrofit and recovery

On Paper 202, a vanilla desert pyramid at start chunk `[-154,402]` was replaced with a 477-piece temple. Its original index was removed and the custom index/references were installed. The backup contained 36 before-chunks, 36 after-chunks and entity data for bounds `[-2505,45,6391]` through `[-2423,114,6473]`.

Unverifiable natural entities initially caused a skip. They were removed only in the disposable acceptance fixture before the successful replacement. A simulated interruption after world save but before the commit checkpoint was recovered on restart with replacement disabled. The operation became `ROLLED_BACK`; **482,230** block states and block-entity data matched the original backup, the original start was restored and the custom start was absent.

Fault injection rejects corrupt checksums and prevents a failed journal write from publishing `COMMITTED`. Claim queries distinguish a verified empty area from unavailable protection. Blocks, used containers and expanded footprints are compared against replay; uncertain entities are preserved. Mandatory recovery cannot be cancelled. Completed revision receipts prevent rescanning on every restart.

An existing End island without reliable complete fight history remains unchanged. That is a deliberate conservative limitation. These checks are samples and failure injections, not an exhaustive test of all seeds, template variants, third-party generators or prolonged multiplayer load.

## Reproduction

See `DEVELOPMENT.en.md`. An already bootstrapped server can supply native dependencies through `-Plbr.paperServerHome=/absolute/path/to/paper-home`.

```sh
python3 tools/structures-probe/build.py /absolute/path/to/paper-home
```

The probe is console-only and restricted to `structures-*` test worlds; its binary is not shipped. `smoke` locates and checks natural structures; `fingerprint` hashes generated chunks; `end-cycle`, `end-resummon`, `end-status` and `gateway` inspect End behavior. `end-cycle` resets the test arena. These are acceptance commands, not production administration features.

Run `structuresprobe end-status minecraft:the_end` first to load the arena; without players, its entities may otherwise be unloaded. Portable generation counts and comparison hashes are in `integrations/lbr-structures/validation/`.
