import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
    `maven-publish`
    id("io.papermc.paperweight.userdev")
}

val libs = the<LibrariesForLibs>()

group = "xyz.xenondevs.invui"
version = "2.0.0-MCHUB-PacketEvents"

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
        languageVersion = JavaLanguageVersion.of(21)
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
            name = "nexus-public"
            url = uri("https://repo.striveservices.org/nexus-public")
            credentials {
                username = "Panda"
                password = System.getenv("PANDA_TOKEN")
            }
        }
    }
}