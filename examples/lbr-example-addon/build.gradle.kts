// 示例：独立 Bukkit/Paper 插件，依赖 LunarCore 提供的 API。
//
// 在本仓库内：根脚本已自动添加 compileOnly(paper-api) 与 compileOnly(project(":lbr-api"))。
// 在你自己的独立工程中请改为：
//
//   repositories {
//       mavenCentral()
//       maven("https://repo.papermc.io/repository/maven-public/")
//       mavenLocal()                                   // 先在 LunarCore 仓库执行 ./gw.sh :lbr-api:publishToMavenLocal
//   }
//   dependencies {
//       compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")
//       compileOnly("com.lunarboreal:lbr-api:1.0.0")   // 或 compileOnly(files("libs/lbr-api-1.0.0.jar"))
//   }
//
// 注意：lbr-api 只能 compileOnly，切勿 shade 进自己的 jar（运行时由 LunarCore 提供）。

tasks.named<Jar>("jar") {
    archiveBaseName.set("LbrExampleAddon")
}
