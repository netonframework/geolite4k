plugins {
    kotlin("multiplatform") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    `maven-publish`
}

group = "com.netonframework"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    applyDefaultHierarchyTemplate()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
