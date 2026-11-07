import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

val moduleProjects = rootProject.subprojects.filter { it.projectDir.parentFile.name == "modules" }
val officialModuleProjects = moduleProjects.filter { it.name != "lbr-structures-web" }
val basicModules = setOf("lbr-essentials")

repositories {
    maven("https://repo.extendedclip.com/releases/") {
        content { includeGroup("me.clip") }
    }
}

dependencies {
    // PlaceholderAPI 桥接（仅在服务器安装了 PAPI 时才会加载桥接类）
    compileOnly("me.clip:placeholderapi:2.11.6") { isTransitive = false }

    // API 打进 core jar，外部插件 depend: [LunarCore] 即可使用
    implementation(project(":lbr-api"))

    compileOnly(rootProject.extra["vaultApi"] as String) { isTransitive = false }
    // 以下运行时通过 plugin.yml 的 libraries 由 Paper 从 Maven Central 下载
    compileOnly(rootProject.extra["javalin"] as String)
    compileOnly(rootProject.extra["hikari"] as String)
    compileOnly(rootProject.extra["sqlite"] as String)
    compileOnly(rootProject.extra["mysql"] as String)
    compileOnly(rootProject.extra["jwt"] as String)
    compileOnly(rootProject.extra["bcrypt"] as String)
    // Paper 服务端自带 log4j2，用于 Web 控制台实时日志
    testImplementation("org.apache.logging.log4j:log4j-core:2.24.3")
    testRuntimeOnly(rootProject.extra["jwt"] as String)
    testRuntimeOnly(rootProject.extra["bcrypt"] as String)
    testImplementation(rootProject.extra["javalin"] as String)
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")
}

moduleProjects.forEach { evaluationDependsOn(it.path) }

val marketResources = layout.buildDirectory.dir("generated/market")
val generateMarketCatalog by tasks.registering {
    val catalog = rootProject.file("module-market/catalog.json")
    inputs.file(catalog)
    inputs.property("version", project.version.toString())
    officialModuleProjects.forEach { dependsOn(it.tasks.named("jar")); inputs.file(it.tasks.named<Jar>("jar").flatMap { jar -> jar.archiveFile }) }
    outputs.dir(marketResources)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val entries = JsonSlurper().parse(catalog) as List<Map<String, Any>>
        val result = entries.map { entry ->
            val id = entry["id"] as String
            val jar = project(":lbr-$id").tasks.named<Jar>("jar").get().archiveFile.get().asFile
            val hash = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
            entry + mapOf("version" to project.version.toString(), "asset" to jar.name, "sha256" to hash, "size" to jar.length())
        }
        val output = marketResources.get().file("module-market.json").asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("version" to project.version.toString(), "modules" to result))))
    }
}
sourceSets.main { resources.srcDir(marketResources) }
tasks.processResources { dependsOn(generateMarketCatalog) }

tasks.jar {
    archiveBaseName.set("LunarCore")
    archiveVersion.set(project.version.toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) { into("META-INF") }
    from(rootProject.file("third-party/lagfixer/upstream/LICENSE")) { into("META-INF/lagfixer") }
    from(rootProject.file("third-party/lagfixer/SOURCE.json")) { into("META-INF/lagfixer") }

    // 合并 API 类
    from(project(":lbr-api").sourceSets["main"].output)

    // Only basic commands ship inside the core. Other modules are separate assets.
    moduleProjects.filter { it.name in basicModules }.forEach { mp ->
        dependsOn(mp.tasks.named("jar"))
        from(mp.tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
            into("builtin-modules")
        }
    }

    // Web 面板前端（web-ui/dist，先执行 web-ui 内 npm run build）
    from(rootProject.file("web-ui/dist")) {
        into("web")
    }
}
