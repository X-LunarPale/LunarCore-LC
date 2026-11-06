rootProject.name = "LunarCore"

include("lbr-api")
include("lbr-core")
include("terrain-agent")
project(":terrain-agent").projectDir = file("tools/terrain-agent")

val modules = listOf(
    "lbr-economy",
    "lbr-signin",
    "lbr-land",
    "lbr-ban",
    "lbr-antixray",
    "lbr-anticheat",
    "lbr-motd",
    "lbr-essentials",
    "lbr-teleport",
    "lbr-chat",
    "lbr-titles",
    "lbr-scoreboard",
    "lbr-permissions",
    "lbr-world",
    "lbr-terrain",
    "lbr-performance",
    "lbr-vote",
    "lbr-journal",
    "lbr-landmarks",
    "lbr-qqauth",
)
modules.forEach {
    include(it)
    project(":$it").projectDir = file("modules/$it")
}

// 独立桥接插件；第三方原插件及其代码不打包到 LunarCore。
listOf("lbr-playertitle-web", "lbr-multiverse-web", "lbr-structures").forEach {
    include(it)
    project(":$it").projectDir = file("integrations/$it")
}

// 扩展开发示例，不打包进 LunarCore。
val examples = listOf(
    "lbr-example-addon",   // 独立 Bukkit 插件形式
    "lbr-example-module",  // LC 模块形式（放入 plugins/LunarCore/modules/ 热加载）
)
examples.forEach {
    include(it)
    project(":$it").projectDir = file("examples/$it")
}
