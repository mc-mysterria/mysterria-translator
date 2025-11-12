plugins {
    `java-library`
    `maven-publish`
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("io.github.goooler.shadow") version "8.1.8"
}

repositories {
    mavenCentral()
    mavenLocal()

    flatDir {
        dirs("libs")
    }

    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
}

dependencies {
    compileOnly(files("libs/ChatControl-11.5.3.jar"))
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation("com.google.genai:google-genai:1.26.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

group = "net.mysterria"
version = "2.0.0"
description = "translator"
java.sourceCompatibility = JavaVersion.VERSION_21

tasks {
    runServer {
        minecraftVersion("1.21.8")
    }
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}
