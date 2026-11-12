// 示例：LC 模块（module.yml + LcModule）。
// 构建：./gw.sh :lc-example-module:jar → examples/lc-example-module/build/libs/lc-example-module.jar
// 部署：放入 plugins/LunarCore/modules/，执行 /lc module enable hello（或重启）即可，无需重启服务器即可热重载。
// 本仓库内已由根脚本添加 compileOnly(paper-api) 与 compileOnly(project(":lc-api"))；
// 独立工程写法见 examples/lc-example-addon/build.gradle.kts 注释。
