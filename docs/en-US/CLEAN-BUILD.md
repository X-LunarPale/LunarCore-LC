# Clean build verification

[中文](../zh-CN/CLEAN-BUILD.md)

Verified on fresh Ubuntu 24.04 GitHub Actions runners on 2026-11-07 using Temurin 25.0.5. No restored Gradle/npm caches, pre-existing Paper server directory or precompiled LC API were used.

- [Core and bridge](https://github.com/rtiags/LunarCore-LC/actions/runs/42347733174), commit `4414ad8ae02e47fd234cbf3818d5e08c408b112e`: 88 core frontend tests, 14 bridge frontend tests, all Gradle Java test tasks, release packaging and market validation passed. Gradle completed in approximately 2m 11s.
- [Standalone generator](https://github.com/rtiags/LunarCoreStructures/actions/runs/42347757015), commit `e19868498fa721faab9ee53f3946c8ed855729e2`: the compatible LC API was first built from pinned commit `f943ea2d916ac74e858c9e07d5aab54e8bc836a7`; the generator then built using a separate empty Gradle cache and the freshly published local API repository. All 14 tests passed, none skipped. The API and generator phases took approximately 49s and 53s.

Reports and binaries are attached as Actions artifacts. Neither log contains FROM-CACHE tasks. This proves a successful cold dependency build, not byte-for-byte reproducibility across toolchain/platform versions.

## Isolation and reproduction

Workflows assert the custom cache directories do not exist before tool installation. Maven local and Java user.home are isolated. Gradle build/configuration caches are disabled, Paper uses the pinned public development bundle, and lbr.paperServerHome is never supplied. The generator consumes only the API compiled in the same fresh pipeline.

The active runs are in the two rtiags repositories. The core workflow is part of PR #4 and reaches upstream only after merge. Actions are pinned to commit SHAs and require only contents:read, without publishing credentials.

For copyable local commands, see the [reproduction block](../zh-CN/CLEAN-BUILD.md#本地复现). Prerequisites: JDK 25, Node.js 24, npm, Python 3 and Git. The commands create new source/cache directories without modifying existing user caches. Checkout the validated commits for an exact source snapshot; do not add --offline, lbr.paperServerHome, old build outputs or precompiled API JARs.

## Limits and initial failure

The first workflow attempt failed at configuration parsing because runner was used in job-level env. Cache initialization was moved into a runner step; both workflows then passed. No business-code workaround was required for the default build path.

A separate macOS fresh clone/cold npm install passed the 88 frontend tests and build. Its slow initial Gradle distribution download was stopped after both Linux CI runs passed; that local Java attempt is not counted as successful. Windows cold builds are not additionally claimed.
