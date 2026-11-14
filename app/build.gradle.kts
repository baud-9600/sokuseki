import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun localString(key: String): String =
    (localProperties.getProperty(key) ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "de.asdfghasdfgh.muditracks"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.asdfghasdfgh.muditracks"
        // The Kompakt runs Android 12 (API 31). Targeting the installed OS keeps the
        // foreground-service and exact-alarm rules predictable.
        minSdk = 31
        targetSdk = 31
        versionCode = 1
        versionName = "0.1.0"

        // Defaults for the first start. They live in the gitignored local.properties,
        // so the private Home Assistant webhook never ends up in the repository.
        buildConfigField("String", "DEFAULT_WEBHOOK_URL", "\"${localString("owntracks.url")}\"")
        buildConfigField("String", "DEFAULT_USER", "\"${localString("owntracks.user")}\"")
        buildConfigField("String", "DEFAULT_DEVICE", "\"${localString("owntracks.device")}\"")
        buildConfigField("String", "DEFAULT_TID", "\"${localString("owntracks.tid")}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            vcsInfo {
                include = false
            }
        }
    }

    lint {
        // Sideloaded onto the Kompakt, not distributed through Google Play.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module")
    }

    sourceSets {
        named("main") { kotlin.srcDir("src/main/kotlin") }
        named("test") { kotlin.srcDir("src/test/kotlin") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // Mudita Mindful Design, resolved from ~/.m2 (see settings.gradle.kts).
    implementation(libs.mmd)

    testImplementation(libs.junit4)
    // The JVM test runtime needs a real org.json; android.jar only has stubs.
    testImplementation(libs.org.json)
}
