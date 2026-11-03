#!/usr/bin/env bash
# 一键构建：前端面板 + 全部模块 + LunarCore.jar
set -e
cd "$(dirname "$0")"
if [ -d ../_tools/tmp ]; then
  export TEMP="$(cd ../_tools/tmp && pwd)" TMP="$(cd ../_tools/tmp && pwd)"
fi
if [ "$1" != "--skip-web" ]; then
  (cd web-ui && { [ -d node_modules ] || npm ci; } && npm run build)
fi
./gw.sh :lbr-core:jar
ls -l lbr-core/build/libs/
