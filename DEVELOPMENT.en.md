# Development and Builds

[简体中文](DEVELOPMENT.md) | [User guide](README.en.md)

This page is for contributors and extension authors. Installing the plugin does not require these steps.

## Build

Use JDK 25 and Node.js/npm. The first build downloads dependencies.

```bash
cd web-ui
npm ci
npm run build
cd ..
./gradlew test :lbr-core:jar :terrain-agent:jar :lbr-multiverse-web:jar :lbr-playertitle-web:jar
python package-release.py
```

Use `gradlew.bat` on Windows. Outputs are in `dist/0.0.6-preview/`. Only basic commands are embedded in the core.

If local Paper tooling fails, use `-Plbr.paperServerHome=server-directory` with a matching complete Paper installation. Normal builds do not require it.

## Test

```bash
./gradlew test
cd web-ui
node --test tests/*.test.js tests/*.test.mjs
npm run test:i18n
```

`tools/verify-module-market-ui.py` needs a local frontend and Playwright. It uses simulated data without modifying a running game server.

## Layout and Rules

`lbr-api/` provides interfaces, `lbr-core/` shared services and the panel, `modules/` features, `integrations/` standalone plugins and `web-ui/` the frontend.

Communicate through APIs rather than importing other module implementations. Keep database queries and downloads off the game thread. Access Bukkit players, worlds and module lifecycle on the main thread. Release tasks, threads and connections on shutdown.

Provide Chinese and English UI text. User descriptions should explain purpose, conditions and results. Keep implementation details in code or developer references.

Use LC as the short name, `/lc` as the main command, `%lc_module_key%` for placeholders and `LC.get()` for API access. Legacy commands, placeholders and the `LBR` entry point remain compatible. Existing `lbr.*` game permissions, data identifiers, project directories and module filenames stay unchanged so upgrades preserve access and data.

## Release

`gradle.properties` defines `0.0.6-preview`, using GitHub tag `v0.0.6-preview`.

`module-market/catalog.json` defines names, descriptions, categories and required modules. The build adds sizes and SHA-256 file checksums. Upload modules from the same package build. Rebuild the core after rebuilding modules; do not mix outputs.

Keep ordinary Bukkit plugins separate from internal modules. Binary releases include corresponding source, build files, GPL v3 and third-party licenses.

Public releases use `X-LunarPale/LunarCore-LC`. The market downloads public Release files directly without an access token. Run `python tools/validate-module-market.py` to check entries and `python tools/test-module-market-validator.py` to test validation behavior. Public snapshots and source archives include current guides, without earlier handoffs or runtime data.
