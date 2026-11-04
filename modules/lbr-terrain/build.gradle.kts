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

tasks.jar {
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
}
