dependencies {
    compileOnly(project(":lc-api"))
    testImplementation(project(":lc-api"))
}
tasks.named<Jar>("jar") {
    archiveBaseName.set("LcMultiverseWeb")
}
