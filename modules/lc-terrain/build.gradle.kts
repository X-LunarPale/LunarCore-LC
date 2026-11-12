plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
}

val localPaper = providers.gradleProperty("lc.paperServerHome").orNull
if (localPaper == null) {
    apply(plugin = "io.papermc.paperweight.userdev")
    dependencies {
        "paperweightDevelopmentBundle"("io.papermc.paper:dev-bundle:26.3.build.202-stable@zip")
    }
} else {
    dependencies {
        compileOnly(rootProject.extra["lcLocalNativeClasspath"] as FileCollection)
        testImplementation(rootProject.extra["lcLocalNativeClasspath"] as FileCollection)
    }
}

tasks.test {
    val terrainAgent = project(":terrain-agent").tasks.named<Jar>("jar")
    dependsOn(terrainAgent)
    inputs.file(terrainAgent.flatMap { it.archiveFile })
    jvmArgs("-javaagent:${terrainAgent.get().archiveFile.get().asFile.absolutePath}")
}

tasks.jar {
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
}
