# Contributing and Module Submissions

[简体中文](CONTRIBUTING.md) | [Module market](module-market/README.en.md)

Everyone may download, modify, report problems and submit modules. Browsing, downloads and in-panel installation require no GitHub token. Submissions need a free GitHub account.

## Submit a Module

1. Publish the source in a public repository and upload the JAR to its Release.
2. Fork this repository and add `module-market/community/module-id.json`.
3. Copy the [example](module-market/submission.example.json). Fill in the name, purpose, versions, download, source, license and file checksum.
4. Select **Contribute → Open pull request**. Include catalog check results. The entry appears in the directory after merging.

If you prefer a form, choose **Issues → New issue → Module submission** and provide a public download link. Both submission routes are open to everyone.

Authors publish and update files in their own Releases without a token for this repository. Catalog changes are merged through PRs; submissions do not directly replace official assets. Community modules currently use manual installation from the GitHub market. The in-panel installer offers the 19 modules released with the core.

## Fields

| Field | Content |
|---|---|
| `id` | Lowercase letters, digits, hyphens or underscores; match the module's `module.yml` |
| `name`, `nameEn` | Chinese and English names |
| `description`, `descriptionEn` | Chinese and English descriptions |
| `version` | Module version |
| `coreVersions` | Tested core versions, such as `["0.0.6-preview"]` |
| `author` | Author name |
| `source` | Public source repository HTTPS URL |
| `download` | Public JAR HTTPS download URL |
| `license` | Open source license |
| `depends` | Required LC module IDs, or `[]` |

Before submitting, run `python tools/validate-module-market.py` to check the format, duplicate IDs and dependencies.
## Project Changes

Run relevant checks from [Development and Builds](DEVELOPMENT.en.md), describe the change and results, and open a PR. Keep Chinese and English guides aligned. Preserve GPL v3 and third-party licenses.

Exclude live-server settings, player data and private keys.
