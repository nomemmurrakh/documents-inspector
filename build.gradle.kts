import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.10.0"
}

group = "com.nomemmurrakh"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio("2025.1.1.14")
        bundledPlugin("org.jetbrains.android")
        testFramework(TestFrameworkType.Platform)
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        name = "Documents Inspector"
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnit()
}
