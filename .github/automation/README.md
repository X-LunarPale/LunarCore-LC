# 目录校验 / Catalog Checks

本目录提供 GitHub Actions 模板。当前发布凭据没有 `workflow` 权限，模板尚未启用。具有相应权限的维护者可将 `module-market.yml` 放入 `.github/workflows/`，提交后自动检查目录变更。

This directory contains an Actions template. The publishing credential lacks `workflow` permission, so it is not active. A maintainer with the required permission can place `module-market.yml` in `.github/workflows/` to check catalog changes automatically.

本地检查 / Local checks:

```bash
python tools/validate-module-market.py
python tools/test-module-market-validator.py
```
