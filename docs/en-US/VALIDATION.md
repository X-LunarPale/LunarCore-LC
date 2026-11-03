# Validation

[简体中文](../zh-CN/VALIDATION.md) | [Release notes](RELEASE.md)

Date: 2026-11-03. Environment: Windows, Java 25 and the Paper 26.3 build 202 compilation environment.

## Checked

- Backend tests passed for existing features and the market. Market coverage includes dependencies, duplicate operations, failed verification, cancellation, removal protection, persisted changes, interruption recovery and rollback after partial application.
- All 81 frontend tests passed, including request handling, permission navigation, paused refresh and translation dictionaries.
- Playwright checks passed for search, categories, install, update, removal, cancellation, restart status and read-only accounts.
- Chinese and English market layouts have no horizontal overflow at 1440 × 1000 and 390 × 844. Market polling stops after leaving the page.
- Frontend and plugin builds completed. Packaging checks that the core embeds only basic commands and validates all 19 module versions, sizes, checksums and ZIP integrity.
- Anonymous downloads from the public repository returned HTTP 200. The token-free panel download test retrieved, verified and applied a published module in a temporary directory. All 27 published asset digests match local files.

Browser checks use simulated APIs. File application tests use temporary directories without modifying the existing game server.

## Not Run by Default

A Windows file-link test may be skipped for insufficient system privileges. The real GitHub download test runs only with `LUNARCORE_MARKET_RELEASE_TEST=true` and passed against the public assets. An Actions validation template is included but not enabled because the publishing credential lacks workflow scope.

Real players, Linux, MySQL, multiple AI providers and long-term load still need target-environment checks. The existing game server was not deployed or restarted.
