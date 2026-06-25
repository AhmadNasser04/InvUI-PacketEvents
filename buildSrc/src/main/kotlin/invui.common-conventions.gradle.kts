import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
    `maven-publish`
}

val libs = the<LibrariesForLibs>()

group = "xyz.xenondevs.invui"
version = "2.2.1-PacketEvents"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly(libs.paper.api)

    compileOnly(libs.packetevents.spigot)
    implementation(libs.jetbrains.annotations)
    implementation(libs.jspecify)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.logback.classic)
    testImplementation(libs.test.paper.api)
    testImplementation(libs.packetevents.spigot)
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/AhmadNasser04/InvUI-PacketEvents")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.username").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.password").orNull
            }
        }
    }
}
