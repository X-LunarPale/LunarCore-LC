// 示例：LBR 模块（module.yml + LbrModule）。
// 构建：./gw.sh :lbr-example-module:jar → examples/lbr-example-module/build/libs/lbr-example-module.jar
// 部署：放入 plugins/LunarCore/modules/，执行 /lbr module enable hello（或重启）即可，无需重启服务器即可热重载。
// 本仓库内已由根脚本添加 compileOnly(paper-api) 与 compileOnly(project(":lbr-api"))；
// 独立工程写法见 examples/lbr-example-addon/build.gradle.kts 注释。
