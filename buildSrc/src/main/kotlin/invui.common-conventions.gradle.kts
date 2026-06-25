import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
    `maven-publish`
    id("io.papermc.paperweight.userdev")
}

val libs = the<LibrariesForLibs>()

group = "xyz.xenondevs.invui"
version = "2.2.0-PacketEvents"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://jitpack.io")
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.get())

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

paperweight {
    addServerDependencyTo.add(configurations.named(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME))
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
