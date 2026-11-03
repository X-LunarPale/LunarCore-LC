dependencies {
    compileOnly(project(":lbr-api"))
    testImplementation(project(":lbr-api"))
}
tasks.named<Jar>("jar") {
    archiveBaseName.set("LbrMultiverseWeb")
}
