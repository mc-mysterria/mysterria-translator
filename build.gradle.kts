plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.4.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.pinodev.it/releases")
    maven("https://mvn.lib.co.nz/public") {
        content { includeGroup("me.libraryaddict.disguises") }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("it.pino.zelchat:zelchat-api:2.0.0-pre-31")
    compileOnly(files("libs/ChatControl-11.5.3.jar"))
    compileOnly("me.libraryaddict.disguises:libsdisguises:11.0.16")
    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
}

group = "net.mysterria"
version = "3.0.0"
description = "translator"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    runServer {
        minecraftVersion("26.3")
    }
}
