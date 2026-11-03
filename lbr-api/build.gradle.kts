plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
}

// ./gw.sh :lbr-api:publishToMavenLocal → ~/.m2/repository/com/lunarboreal/lbr-api/<version>/
// 第三方扩展：repositories { mavenLocal() } + compileOnly("com.lunarboreal:lbr-api:<version>")
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "lbr-api"
            from(components["java"])
            pom {
                name.set("LunarCore API")
                description.set("琉月·冰澜 LunarCore 扩展开发 API（模块 / 外部插件 / Web 面板）")
            }
        }
    }
    repositories {
        // ./gw.sh :lbr-api:publishMavenPublicationToDistRepository → <仓库根>/build/repo，
        // 适合 ~/.m2 不可写或需要把 API 随发布包分发的场景：maven { url = uri("<路径>/build/repo") }
        maven {
            name = "dist"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}
