plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
}

val localPaper = providers.gradleProperty("lbr.paperServerHome").orNull
if (localPaper == null) {
    apply(plugin = "io.papermc.paperweight.userdev")
    dependencies {
        "paperweightDevelopmentBundle"("io.papermc.paper:dev-bundle:26.3.build.202-stable@zip")
    }
} else {
    val serverHome = file(localPaper)
    val serverJar = serverHome.resolve("versions/26.3/paper-26.3.jar")
    require(serverJar.isFile) { "Expected bootstrapped Paper 26.3 at $serverJar" }
    dependencies {
        compileOnly(files(serverJar))
        compileOnly(fileTree(serverHome.resolve("libraries")) { include("**/*.jar") })
        testImplementation(fileTree(serverHome.resolve("libraries")) { include("**/gson-*.jar") })
        testImplementation(files(serverJar))
        testImplementation(fileTree(serverHome.resolve("libraries")) { include("**/*.jar") })
    }
}

tasks.jar {
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
}
