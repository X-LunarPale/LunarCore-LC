# Project attribution and third-party notices / 项目署名与第三方声明

## QQAuth / QQ 认证模块

QQAuth 由 LunarCore 作者与 [qqauth-private](https://github.com/crx97220130101-dotcom/qqauth-private) 的协作者共同开发。当前发行包从 `modules/lc-qqauth` 构建模块，随 LunarCore 以 GPL v3 发布，并保留 QQAuth 既有贡献者署名和许可说明。

QQAuth is jointly developed by LunarCore's author and the qqauth-private collaborators. This distribution builds the module from `modules/lc-qqauth` under GPL v3, preserving existing QQAuth contributor credits and license notices.

这是一个 LunarCore 模块，并非独立 Bukkit 插件。
启用 / 关闭该模块可能会导致在线玩家断开连接，且必须配置桥接令牌、分组信息才能正常使用。

- 全新安装：默认配置为 `modules.disabled: [qqauth]`、`modules.qqauth-autostart: false`
- 已有旧配置：如果没有主动开启该功能，QQAuth 同样默认不会自动加载

**配置步骤**：先完成桥接与分组配置，**在服务端停止状态下**，手动开启启用选项、并把 qqauth 从禁用列表移除。
发布包附带源码与修改记录。
 **这是一个LunarCore模块，不是一个Minecraft服务器插件！**
 你可以参阅[开发文档](https://github.com/X-LunarPale/LunarCore/blob/master/DEVELOPMENT.md)以自行开发此模块

## LagFixer

LunarCore performance内置优化包含基于LagFixer源码的GPL适配。此声明更正早期版本“未复制或打包LagFixer源码”的表述；本次分发含原始源码快照和单独维护的LC适配代码。原作者及原项目不会因移植而改为LC原创。

| 项目 | 固定信息 |
|---|---|
| 原项目 | [lajczik/lagfixer](https://github.com/lajczik/lagfixer) |
| 作者 | lychee；仓库所有者lajczik，以原始源码及元数据声明为准 |
| 标签 / 版本 | build-151 / 1.7.1 |
| 来源commit | `0dede033f7dc2950a08f8a461bfcff84f64c7d3c` |
| 不可变在线来源 | [固定commit源码](https://github.com/lajczik/lagfixer/tree/0dede033f7dc2950a08f8a461bfcff84f64c7d3c) |
| 本地来源记录 | [SOURCE.json](<third-party/lagfixer/SOURCE.json>) |
| 未修改原始快照 | [README](<third-party/lagfixer/upstream/README.md>)及其所在upstream目录 |
| 原许可证全文 | [上游LICENSE](<third-party/lagfixer/upstream/LICENSE>)，GNU GPL version3 |
| LC项目许可证 | [LICENSE](<LICENSE>)，GPL-3.0-only |

### 修改说明（2026-10-19）

适配实现位于[EmbeddedLagFixer](<modules/lc-performance/src/main/java/com/lunarboreal/performance/lagfixer/EmbeddedLagFixer.java>)及同包协作文件，与原始快照分开。修改包括LC模块生命周期、配置命名空间、中文Web面板/权限、主线程任务、Paper/Bukkit事件适配及Paper26.3 NMS/CraftBukkit AI与载具适配、默认全部关闭、有界处理与状态计数。没有将上游插件改名后冒充原作者作品，也不宣称原作者认可本次修改。

上游ModuleManager注册12项：MobAiReducer、LagShield、RedstoneLimiter、EntityLimiter、ConsoleFilter、WorldCleaner、VehicleMotionReducer、InstantLeafDecay、AbilityLimiter、ExplosionOptimizer、AFKOptimizer、TrashDisposal。上游另有HopperOptimizer资源但未注册，LC本次补接漏斗，总计暴露13个内置开关。上游原配置多项默认开启，LC总开关全部false；其自动报告/更新/bStats和历史日志删除不因此自动引入。

上游包含版本化NMS实现至v26_2；LC事件模块使用公开API，AI/载具模块在nms子包中包含针对Paper26.3重新编译的NMS/CraftBukkit移植，保留上游来源并增加关闭恢复逻辑，不直接加载旧v26_2二进制；没有宣称完整复刻所有封包和第三方插件hook。原始快照仅保存来源，不作为插件自动启动。独立LagFixer发布JAR未随包分发；可选的固定monitor桥接是另一条功能路径，上游Paper26.3兼容性尚未验证。内置适配的最终构建与实服证据由集成测试补充，不承诺TPS提升。

### 分发与对应源码

发布包含本声明、完整GPLv3文本、固定上游快照、LC修改源码和构建文件，供接收者按GPLv3查看、修改和重新分发。分发二进制时必须同时按GPLv3提供完整对应源码与必要构建材料；仅一个网页链接或本声明不能替代实际源码交付。具体包内容、构建步骤和校验值由对应发布包列出。保留上游文件自带版权/许可说明；不要删除快照或将其许可证替换成项目署名。

### 上游依赖及可选集成

上游plugin构建声明Adventure platform/minimessage为implementation，其他多为compileOnly：Paper/Spigot支持、PlaceholderAPI、spark、WildStacker、RoseStacker、ModelEngine、MythicMobs、UltimateStacker、StackMob、LevelledMobs、BetterModel、PacketEvents及若干平台库；plugin.yml还声明OSHI运行库。原始源码快照中保留这些构建信息，不代表这些库二进制都由LC重新分发，也不代表它们在LC适配中全部可用。

若将来实际打包额外第三方库或上游JAR，应按各实际依赖许可证补全版权、许可及对应源码义务；不能仅依赖LagFixer自身GPL声明覆盖所有依赖。当前适配的运行依赖以LC构建文件和实际发布清单为准，不自动下载/启用上游构建中的可选插件。

本声明不改变第三方原有权利。完整使用与功能边界见[性能模块说明](<docs/PERFORMANCE.md>)。

## Tectonic

`modules/lc-terrain` adapts Apollo's MIT-licensed Tectonic 3.0.29 mod model for
Minecraft 26.3 to Paper 26.3. The pinned official Fabric artifact,
SHA-512, MIT text, author attribution and reproducible asset extraction are
recorded in `modules/lc-terrain/src/main/resources/upstream/TECTONIC-NOTICE.txt`
and the adjacent `extract-model.ps1` and `extract-features.ps1` scripts. The
module JAR includes that notice, the mod's terrain model and selected feature
and overlay resources; Minecraft's base resources are read from the server at
runtime and are not redistributed in those assets.

The adaptation provides thirty-four advanced options and five official
parameter presets, resolves configuration functions to native per-world density
graphs, registers default or custom height ranges and uses Paper's native noise
generator. Native codecs preserve the pinned configuration-noise arithmetic,
experimental sampling behavior and parameter-noise seed aliases. The port also
adapts the ultrasmooth overlay, island predicate, underground-river lichen,
lanterns/chains and ice, twelve ore/gravel placement overrides, and the three
vanilla carvers covered by the official no-carvers overlay. Generation settings
are attached to each generator without replacing shared biomes.

The corresponding slope-range, density-filter and noise-slope behavior is
adapted from Apollo's Lithostitched 26.3 reference at commit
`9a12e653447f1c4c9c489107d4cbb7c82bc78afe`. Its Fabric and NeoForge metadata
declare MIT; attribution is included in the bundled Tectonic notice. No
Lithostitched runtime, mod loader or data pack is installed or distributed.

The startup agent in `tools/terrain-agent` adapts per-world temperatures,
client snowlines, ocean monument offsets, old-chunk blending and height
compatibility. The module adds frozen-ocean climate replacement, custom
minimum and maximum bounds, a minY-relative lava floor and `/tectonic debug`
and the eight terrain locators. The pinned artifact's ineffective
spaghetti and alternate-continent switches are not presented as working
controls. This is not an official Tectonic release or an endorsement by its
author, and it does not promise block-for-block identity. The module README
records the supported parameters, functional boundaries and verification scope.

The startup agent bundles ASM and ASM Commons 9.9.1 from the OW2 ASM project.
Their BSD license is included in the agent JAR at `META-INF/ASM-LICENSE.txt`.

## WebUI QR codes

The WebUI bundles `qrcode` 1.5.4 and its `dijkstrajs` browser dependency for
local TOTP QR code rendering. Their MIT notices are retained below.

Copyright (c) 2012 Ryan Day

Dijkstra path-finding functions, adapted from the Dijkstar Python project:
Copyright (C) 2008 Wyatt Baldwin <self@wyattbaldwin.com>. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
