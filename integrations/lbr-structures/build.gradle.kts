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
    dependencies {
        compileOnly(rootProject.extra["lbrLocalNativeClasspath"] as FileCollection)
        testImplementation(rootProject.extra["lbrLocalNativeClasspath"] as FileCollection)
    }
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2")
    testImplementation("com.google.code.gson:gson:2.13.2")
}

tasks.jar {
    archiveBaseName.set("LunarCoreStructures")
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) { into("META-INF") }
    from(rootProject.file("third-party/yungs")) {
        include("*/SOURCE.json", "*/upstream/LICENSE")
        into("META-INF/yungs")
    }
}
