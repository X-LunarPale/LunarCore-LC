version = "0.0.6-preview.1"

val webDir = layout.projectDirectory.dir("web")
val installWeb by tasks.registering(Exec::class) {
    workingDir(webDir)
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.files(webDir.file("package.json"), webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("node_modules"))
}
val buildWeb by tasks.registering(Exec::class) {
    dependsOn(installWeb)
    workingDir(webDir)
    commandLine("npm", "run", "build")
    inputs.dir(webDir.dir("src"))
    inputs.files(webDir.file("index.html"), webDir.file("vite.config.js"), webDir.file("package-lock.json"))
    outputs.dir(webDir.dir("dist"))
}
tasks.processResources {
    dependsOn(buildWeb)
    from(webDir.dir("dist")) { into("web") }
}
tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
