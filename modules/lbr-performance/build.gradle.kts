plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
}

// Standard builds use Paper's pinned development bundle. An explicitly provided
// already-bootstrapped Paper server is useful when Codebook ZIPFS is unavailable
// on the build host; it is compile-only and never enters the distributed JAR.
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
    }
}

val probeClasses = layout.buildDirectory.dir("lagfixer-probe/classes")
val compileLagFixerProbe by tasks.registering(JavaCompile::class) {
    source(rootProject.fileTree("tools/lagfixer-probe/src/main/java") { include("**/*.java") })
    classpath = configurations.compileClasspath.get()
    destinationDirectory.set(probeClasses)
    javaCompiler.set(javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) })
    options.release.set(25)
    options.encoding = "UTF-8"
}
tasks.register<Jar>("lagfixerProbeJar") {
    dependsOn(compileLagFixerProbe)
    archiveFileName.set("LagFixerProbe.jar")
    destinationDirectory.set(layout.buildDirectory.dir("lagfixer-probe"))
    from(probeClasses)
    from(rootProject.file("tools/lagfixer-probe/src/main/resources"))
}

tasks.jar {
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
    from(rootProject.file("third-party/lagfixer/upstream/LICENSE")) {
        into("META-INF/lagfixer")
    }
    from(rootProject.file("third-party/lagfixer/SOURCE.json")) {
        into("META-INF/lagfixer")
    }
}
