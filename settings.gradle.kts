rootProject.name = "LunarCore"

include("lc-api")
include("lc-core")
include("terrain-agent")
project(":terrain-agent").projectDir = file("tools/terrain-agent")

val modules = listOf(
    "lc-economy",
    "lc-signin",
    "lc-land",
    "lc-ban",
    "lc-antixray",
    "lc-anticheat",
    "lc-motd",
    "lc-essentials",
    "lc-teleport",
    "lc-chat",
    "lc-titles",
    "lc-scoreboard",
    "lc-permissions",
    "lc-world",
    "lc-terrain",
    "lc-performance",
    "lc-vote",
    "lc-journal",
    "lc-landmarks",
    "lc-qqauth",
    "lc-structures-web",
)
modules.forEach {
    include(it)
    project(":$it").projectDir = file("modules/$it")
}

// 独立桥接插件；第三方原插件及其代码不打包到 LunarCore。
listOf("lc-playertitle-web", "lc-multiverse-web").forEach {
    include(it)
    project(":$it").projectDir = file("integrations/$it")
}

// 扩展开发示例，不打包进 LunarCore。
val examples = listOf(
    "lc-example-addon",   // 独立 Bukkit 插件形式
    "lc-example-module",  // LC 模块形式（放入 plugins/LunarCore/modules/ 热加载）
)
examples.forEach {
    include(it)
    project(":$it").projectDir = file("examples/$it")
}
