# 空缓存构建验证

[English](../en-US/CLEAN-BUILD.md)

2026-11-07 已在全新的 Ubuntu 24.04 GitHub Actions runner 上通过验证，JDK 为 Temurin 25.0.5。核心前端、核心 Java 工程、桥接模块和独立生成插件均从源码构建；没有恢复 Gradle/npm 缓存，没有使用现成 Paper 服务端目录，也没有使用预先编译的 LC API。

## 可核查结果

- [核心与桥接 CI](https://github.com/rtiags/LunarCore-LC/actions/runs/42347733174)：验证提交 `4414ad8ae02e47fd234cbf3818d5e08c408b112e`。88 项核心前端测试、14 项桥接前端测试、全部 Gradle Java 测试任务、发行打包和市场目录校验通过。Gradle 构建耗时约 2 分 11 秒。
- [独立生成插件 CI](https://github.com/rtiags/LunarCoreStructures/actions/runs/42347757015)：验证提交 `e19868498fa721faab9ee53f3946c8ed855729e2`。先从固定提交 `f943ea2d916ac74e858c9e07d5aab54e8bc836a7` 构建 LC API，再使用另一份空 Gradle 缓存构建生成插件。14 项 Java 测试全部通过，无跳过；两个 Gradle 阶段分别约 49 秒和 53 秒。

核心 JUnit 报告共 489 项：488 通过、1 项按设计跳过。跳过的是 `ModuleMarketReleaseTest.downloadsPublishedModuleAndAppliesVerifiedJar()`，仅在显式设置 `LUNARCORE_MARKET_RELEASE_TEST=true` 时检查真实已发布市场附件；默认构建 CI 没有启用它。生成插件另有 14 项通过、0 跳过。

测试报告和构建产物保存在对应运行的 Actions artifacts。日志中均没有 FROM-CACHE 任务。这里验证的是“从空依赖缓存成功构建”，不声称不同 JDK/平台产物逐字节相同。

## 隔离方式

工作流使用新 runner，并在工具安装前检查自定义 Gradle/npm 缓存目录不存在；独立设置 Maven 本地仓库和 Java user.home，阻止隐式使用已有 ~/.m2。Gradle 使用 `--no-build-cache --no-configuration-cache`，Paper 通过固定版本的公开 development bundle 构建，不传 `lc.paperServerHome`。生成插件所用 API 来自同一次干净流水线的源码编译产物。

当前自动检查运行在 rtiags 的两个仓库。核心工作流随 PR #4 提交，合并后上游才具有同样的自动检查。Actions 的版本均固定到提交 SHA；构建仅需 contents:read，不依赖发布令牌。

## 本地复现

先安装 JDK 25、Node.js 24、npm、Python 3 和 Git，并设置 JAVA_HOME。下面新建独立目录，不清理或改写用户现有缓存：

```bash
LC_CLEAN_ROOT="$(mktemp -d)"
LC_CLEAN_ROOT="$(cd "$LC_CLEAN_ROOT" && pwd -P)"
git clone --branch codex/yungs-paper-structures --single-branch https://github.com/rtiags/LunarCore-LC.git "$LC_CLEAN_ROOT/core"
git clone https://github.com/rtiags/LunarCoreStructures.git "$LC_CLEAN_ROOT/generator"
mkdir -p "$LC_CLEAN_ROOT/maven-empty" "$LC_CLEAN_ROOT/java-home"
export GRADLE_USER_HOME="$LC_CLEAN_ROOT/gradle-core"
export npm_config_cache="$LC_CLEAN_ROOT/npm"
export npm_config_userconfig="$LC_CLEAN_ROOT/npm-user.conf"
touch "$npm_config_userconfig"
cd "$LC_CLEAN_ROOT/core/web-ui"
npm ci --no-audit --no-fund
node --test tests/*.test.js tests/*.test.mjs
npm run build
cd ..
./gradlew test :lc-core:jar :terrain-agent:jar :lc-multiverse-web:jar :lc-playertitle-web:jar :lc-structures-web:jar :lc-api:publishMavenPublicationToDistRepository   --no-daemon --no-build-cache --no-configuration-cache --max-workers=2   -Duser.home="$LC_CLEAN_ROOT/java-home" -Dmaven.repo.local="$LC_CLEAN_ROOT/maven-empty"
npm test --prefix modules/lc-structures-web/web
python3 package-release.py
cd "$LC_CLEAN_ROOT/generator"
GRADLE_USER_HOME="$LC_CLEAN_ROOT/gradle-generator" ./gradlew :plugin:test :plugin:jar   -PlunarCoreRepository="$LC_CLEAN_ROOT/core/build/repo"   --no-daemon --no-build-cache --no-configuration-cache --max-workers=2   -Duser.home="$LC_CLEAN_ROOT/java-home" -Dmaven.repo.local="$LC_CLEAN_ROOT/maven-empty"
```

如需精确复现上述证据，在各仓库先 checkout 对应验证提交。不要增加 --offline 或 lc.paperServerHome 参数，也不要把旧缓存、旧 build 目录或已有 API JAR 复制进去。

## 边界与失败记录

首轮新建 CI 因在 job env 使用了不允许的 runner 上下文而未进入编译，已改为在 runner 分配后的步骤初始化缓存目录，随后重跑通过。未发现必须修改业务代码才能完成默认构建的问题。

随后[核心复跑](https://github.com/rtiags/LunarCore-LC/actions/runs/42348286771) 在 QQAuth 的 OneBotLifecycleTest 中遇到 8 秒重连等待超时。客户端单次连接/发送本身允许 10 秒，原测试预算不足以覆盖这条超时链；测试统一改为有界的 30 秒等待，并给第二个测试套接字补充读取超时。消息分片、回复内容、重连成功、调用次数及关闭断言均保留，不跳过或自动重试该测试。最终状态以本分支最新 Clean build 运行及 PR 检查为准。

本机 macOS 上另外验证了新 clone、空 npm 缓存的前端安装、88 项测试和构建。其空 Gradle 缓存的 Java 构建因发行包下载较慢，在两条 Linux CI 成功后停止；不把该本机 Java 尝试记为通过。本报告不额外声称 Windows 空缓存构建已验证。
