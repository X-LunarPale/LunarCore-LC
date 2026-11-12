dependencies {
    compileOnly(rootProject.extra["bcrypt"] as String)
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation(rootProject.extra["bcrypt"] as String)
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly(rootProject.extra["sqlite"] as String)
}

tasks.jar {
    from("licenses/QQAuth-UPSTREAM-LICENSE") { into("META-INF") }
}
