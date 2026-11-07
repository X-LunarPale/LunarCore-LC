# Validation

[简体中文](../zh-CN/VALIDATION.md) | [Release notes](RELEASE.md)

## v0.0.7 Release Checks

Date: 2026-11-07. Environment: Windows, Java 25 and local Paper 26.3 build 238.

- Java: 489 tests, with 487 passed, 2 skipped and no failures, including 6 structure management tests.
- Frontend: all 88 core-panel and 14 structure-page tests passed.
- Module catalog: 19 official modules and 1 community entry passed validation; all 3 validator tests passed.
- Core, module and related frontend builds completed.

This build reused dependency caches; it was not a clean-cache build. After the declared dependency download timed out, local compilation used PlaceholderAPI 2.12.3 instead of 2.11.6. It was used only for compilation and is not bundled.

Real QQ group connectivity, multiple AI providers and long-term load were not checked in this run.

## Feature Test Records

- 2026-11-04: 64 terrain, 8 startup-agent and 18 related frontend tests passed. An isolated Paper server checked custom height, temperature isolation, old-chunk handling and world unload/reload. Desktop and mobile page checks passed.
- 2026-11-05: 11 hub, 58 QQAuth Java and 9 related frontend tests passed. The bundled and standalone AstrBot plugins each passed 12 Python tests. Core and module JAR builds completed.
- See [structure validation](STRUCTURES-VALIDATION.md) for structure builds and server checks, and [clean build records](CLEAN-BUILD.md) for builds without dependency caches.

These records do not establish real QQ group connectivity, compatibility with every third-party plugin or long-term load behavior.

## Historical Market Checks

Date: 2026-11-03. Environment: Windows, Java 25 and the Paper 26.3 build 202 compilation environment. The following records describe the v0.0.6 release checks at that time.

### Checked

- Backend tests passed for existing features and the market. Market coverage includes dependencies, duplicate operations, failed verification, cancellation, removal protection, persisted changes, interruption recovery and rollback after partial application.
- All 81 frontend tests passed, including request handling, permission navigation, paused refresh and translation dictionaries.
- Playwright checks passed for search, categories, install, update, removal, cancellation, restart status and read-only accounts.
- Chinese and English market layouts have no horizontal overflow at 1440 × 1000 and 390 × 844. Market polling stops after leaving the page.
- Frontend and plugin builds completed. Packaging checks that the core embeds only basic commands and validates all 19 module versions, sizes, checksums and ZIP integrity.
- Anonymous downloads from the public repository returned HTTP 200. The token-free panel download test retrieved, verified and applied a published module in a temporary directory. All 27 published asset digests match local files.

Browser checks use simulated APIs. File application tests use temporary directories without modifying the existing game server.

### Not Run at That Time

A Windows file-link test may be skipped for insufficient system privileges. The real GitHub download test runs only with `LUNARCORE_MARKET_RELEASE_TEST=true` and passed against the public assets. An Actions validation template is included but not enabled because the publishing credential lacks workflow scope.

Real players, Linux, MySQL, multiple AI providers and long-term load still need target-environment checks. The existing game server was not deployed or restarted.
