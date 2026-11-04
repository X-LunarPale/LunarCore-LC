import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    java
}

val lbrVersion: String by project

// 固定 Paper 26.3 构建；运行时库由 plugin.yml libraries 从 Maven Central 加载
extra["paperApi"] = "io.papermc.paper:paper-api:26.3.build.202-stable"
extra["vaultApi"] = "com.github.MilkBowl:VaultAPI:1.7.1"
extra["javalin"] = "io.javalin:javalin:6.7.0"
extra["hikari"] = "com.zaxxer:HikariCP:7.1.0"
extra["sqlite"] = "org.xerial:sqlite-jdbc:3.53.4.0"
extra["mysql"] = "com.mysql:mysql-connector-j:26.7.0"
extra["jwt"] = "com.auth0:java-jwt:4.6.2"
extra["bcrypt"] = "at.favre.lib:bcrypt:0.10.2"

// A server directory may retain libraries from several Paper builds. Resolve only
// the bundle matching the patched server hash, rather than mixing all cached JARs.
val localPaperHome = providers.gradleProperty("lbr.paperServerHome").orNull
if (localPaperHome != null) {
    val home = file(localPaperHome)
    val nativeJar = home.resolve("versions/26.3/paper-26.3.jar")
    require(nativeJar.isFile) { "Expected bootstrapped Paper 26.3 at $nativeJar" }
    val hash = MessageDigest.getInstance("SHA-256").digest(nativeJar.readBytes())
        .joinToString("") { "%02x".format(it) }
    val entries = home.listFiles().orEmpty().filter { it.isFile && it.extension == "jar" }.firstNotNullOfOrNull { jar ->
        runCatching {
            ZipFile(jar).use { zip ->
                val versions = zip.getEntry("META-INF/versions.list") ?: return@use null
                val matches = zip.getInputStream(versions).bufferedReader().use { reader ->
                    reader.readLines().any { it.substringBefore('\t') == hash }
                }
                if (!matches) return@use null
                zip.getInputStream(zip.getEntry("META-INF/libraries.list")).bufferedReader().use { it.readLines() }
            }
        }.getOrNull()
    } ?: error("Keep the matching Paper server bundle JAR in $home for local native builds")
    val libraries = entries.filter { it.isNotBlank() }.map { home.resolve("libraries/" + it.substringAfterLast('\t')) }
    require(libraries.all { it.isFile }) { "Bootstrap the matching Paper bundle to extract all native dependencies" }
    extra["lbrLocalNativeClasspath"] = files(nativeJar, libraries)
}

allprojects {
    group = "com.lunarboreal"
    version = lbrVersion

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MilkBowl") }
        }
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching(listOf("plugin.yml", "paper-plugin.yml", "module.yml")) {
            expand(props)
        }
    }

    dependencies {
        if (localPaperHome == null || project.name !in setOf("lbr-terrain", "lbr-structures")) {
            "compileOnly"(rootProject.extra["paperApi"] as String)
            "testImplementation"(rootProject.extra["paperApi"] as String)
        }
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        val testTemp = layout.buildDirectory.dir("test-tmp")
        systemProperty("java.io.tmpdir", testTemp.get().asFile.absolutePath)
        doFirst { testTemp.get().asFile.mkdirs() }
    }
}

// 功能模块单独构建，核心仅内置基础模块。
configure(subprojects.filter { it.path != ":lbr-api" && it.path != ":lbr-core" }) {
    dependencies {
        "compileOnly"(project(":lbr-api"))
        "testImplementation"(project(":lbr-api"))
    }
    tasks.named<Jar>("jar") {
        archiveBaseName.set(project.name)
        archiveVersion.set("")
    }
}
