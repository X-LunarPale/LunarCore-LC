plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
}

val agentLibraries by configurations.creating

dependencies {
    agentLibraries("org.ow2.asm:asm:9.9.1")
    agentLibraries("org.ow2.asm:asm-commons:9.9.1")
}
configurations.compileOnly { extendsFrom(agentLibraries) }
configurations.testImplementation { extendsFrom(agentLibraries) }

val bridgeJar = tasks.register<Jar>("bridgeJar") {
    dependsOn(tasks.compileJava)
    archiveFileName.set("LC-Terrain-Bridge.jar")
    from(sourceSets.main.get().output.classesDirs) {
        include("com/lunarboreal/terrain/agent/NativeTerrainHooks*.class")
    }
}

tasks.jar {
    dependsOn(bridgeJar)
    archiveFileName.set("LC-Terrain-Agent.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(agentLibraries.map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class")
    exclude("com/lunarboreal/terrain/agent/NativeTerrainHooks*.class")
    from(bridgeJar) { into("META-INF") }
    from("ASM-LICENSE.txt") { into("META-INF") }
    manifest.attributes(
        "Premain-Class" to "com.lunarboreal.terrain.agent.NativeTerrainAgent",
        "Can-Retransform-Classes" to "true",
        "Can-Redefine-Classes" to "true"
    )
}

val localPaper = providers.gradleProperty("lc.paperServerHome").orNull
if (localPaper == null) {
    apply(plugin = "io.papermc.paperweight.userdev")
    dependencies {
        "paperweightDevelopmentBundle"("io.papermc.paper:dev-bundle:26.3.build.202-stable@zip")
    }
} else {
    val serverHome = file(localPaper)
    dependencies {
        testImplementation(files(serverHome.resolve("versions/26.3/paper-26.3.jar")))
        testImplementation(fileTree(serverHome.resolve("libraries")) { include("**/*.jar") })
    }
}

tasks.test {
    dependsOn(tasks.jar)
    jvmArgs("-javaagent:${tasks.jar.get().archiveFile.get().asFile.absolutePath}")
}
