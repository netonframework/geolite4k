plugins {
    kotlin("multiplatform") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    `maven-publish`
    signing
}

// Kotlin 版本下限 2.4.20：低于它（包括 2.4.20 的 Beta / RC）直接拒绝编译。
// Neton 各仓走同一条 Kotlin 线 —— 混用时 K/N klib 与 KSP 产物会以很难看懂的方式失败。
run {
    val required = KotlinVersion(2, 4, 20)
    val actual = org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)
    val parts = actual.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val parsed = KotlinVersion(parts[0], parts[1], parts[2])
    require(parsed > required || (parsed == required && '-' !in actual)) {
        "Kotlin $actual is below the required minimum $required. Upgrade the Kotlin Gradle plugin."
    }
}

// com.netonstream 是本组织在 Maven Central 上已验证的 namespace。
// geolite4k 是独立库（不是 neton-* 框架模块），所以不带 neton- 前缀，也走自己的版本线。
group = "com.netonstream"
version = "0.1.1"

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
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// ---------- Maven Central 发布 ----------
//
// 纯 Kotlin/Native，没有原生依赖，一台 macOS 机器就能构建并发布全部五个目标。
//
// 发布走 Central Portal 的 bundle 上传，不用 OSSRH staging 端点：后者按客户端 IP
// 划分隐式 staging 仓库，长时间上传中途出口 IP 变化会把一次发布劈成两半。
//   ./gradlew publishAllPublicationsToStagingLocalRepository
// 然后按 RELEASING.md 打包上传。
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("geolite4k")
            description.set("Pure Kotlin/Native reader for MaxMind DB (.mmdb) files, with no JVM or JNI dependency.")
            url.set("https://github.com/netonframework/geolite4k")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("zoujiaqing")
                    name.set("zoujiaqing")
                    email.set("zoujiaqing@gmail.com")
                }
            }
            scm {
                url.set("https://github.com/netonframework/geolite4k")
                connection.set("scm:git:https://github.com/netonframework/geolite4k.git")
                developerConnection.set("scm:git:ssh://git@github.com/netonframework/geolite4k.git")
            }
        }
    }

    repositories {
        // 本地文件仓库：产出可直接打成 Central Portal bundle 的目录树（含签名与校验和）
        maven {
            name = "stagingLocal"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

// Central 强制签名。优先内存 key（CI / 无 keyring 的机器），其次本地 keyring。
signing {
    val inMemoryKey = findProperty("signingInMemoryKey") as String?
    if (!inMemoryKey.isNullOrBlank()) {
        useInMemoryPgpKeys(inMemoryKey, findProperty("signingInMemoryKeyPassword") as String? ?: "")
        sign(publishing.publications)
    } else if (hasProperty("signing.keyId")) {
        sign(publishing.publications)
    }
}

// 不加 javadoc jar：klib 打包的 KMP publication，Central 校验不要求它
// （hyper4k 与 neton 全部模块都是这样发布并通过校验的）。多个 publication 共用一个
// javadoc jar 还会让多个 Sign 任务写同一个 .asc，Gradle 直接报输出冲突。
