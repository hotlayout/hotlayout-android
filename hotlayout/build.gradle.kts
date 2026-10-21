plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    `maven-publish`
    signing
}

group = "com.hotlayout"

android {
    namespace = "com.hotlayout"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    testOptions {
        unitTests {
            // Compose and android.icu run on the JVM through Robolectric: rules, tables and pixels
            // are tested without an emulator.
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    // `api`: `Modifier`, `Dp`, `FontFamily` and `Job` appear in the signatures of the public API.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.kotlinx.coroutines.core)
    // The system back key: one step back in the reader's own stack (`render/modal/SystemBack.kt`).
    implementation(libs.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}

// The tests run once, on the debug variant (the compose test activity is a debug dependency).
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.hostTests.values.forEach { test -> test.enable = false } }
}

// Maven Central publication. The coordinates are `com.hotlayout:hotlayout-android`.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "hotlayout-android"
                version = project.version.toString()
                pom {
                    name.set("HotLayout for Android")
                    description.set("A Jetpack Compose reader of HotLayout server-driven UI documents.")
                    url.set("https://github.com/hotlayout/hotlayout-android")
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("hotlayout")
                            name.set("HotLayout")
                            url.set("https://hotlayout.com")
                            email.set("contact@hotlayout.com")
                        }
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/hotlayout/hotlayout-android/issues")
                    }
                    scm {
                        url.set("https://github.com/hotlayout/hotlayout-android")
                        connection.set("scm:git:https://github.com/hotlayout/hotlayout-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/hotlayout/hotlayout-android.git")
                    }
                }
            }
        }
        // A local repository that `scripts/publish-central.sh` zips into a bundle for the Central Portal.
        repositories {
            maven {
                name = "staging"
                url = uri(layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }
}

// Signing reads the key from Gradle properties (`signingInMemoryKey`, `signingInMemoryKeyPassword`, for example
// from `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_…` variables). Without a key nothing is signed.
signing {
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, providers.gradleProperty("signingInMemoryKeyPassword").orNull)
        afterEvaluate { sign(publishing.publications["release"]) }
    }
}
