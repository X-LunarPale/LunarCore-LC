# 目录校验 / Catalog checks

`.github/workflows/clean-build.yml` 已启用：在全新 Linux runner 上下载依赖、测试和构建核心及桥接，并检查模块市场目录与发行打包。它不恢复 Gradle/npm 缓存，也不使用已有 Paper 服务端目录。

本目录的 `module-market.yml` 保留为仅执行目录检查的独立模板；对应检查已包含在 clean-build 工作流中，无需重复启用。历史上凭据缺少 workflow 权限的限制已不适用于当前 rtiags 发布账号。

The active `.github/workflows/clean-build.yml` builds and tests the core and bridge on a fresh Linux runner, without restored Gradle/npm caches or a pre-existing Paper server. It also validates the module catalog and release packaging. This directory retains a catalog-only template; those checks already run in the active workflow.

本地检查 / Local checks:

```bash
python3 tools/validate-module-market.py
python3 tools/test-module-market-validator.py
```
