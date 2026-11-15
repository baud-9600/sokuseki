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
    namespace = "de.asdfghasdfgh.sokuseki"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.asdfghasdfgh.sokuseki"
        // The Kompakt runs Android 12 (API 31). Targeting the installed OS keeps the
        // foreground-service and exact-alarm rules predictable.
        minSdk = 31
        targetSdk = 31
        // The release tag packs the version: major * 10_000_000 + minor * 10_000 + patch.
        // v0.1.1 -> 10_001.
        versionCode = 10_001
        versionName = "0.1.1"

        // Defaults for the first start. They live in the gitignored local.properties,
        // so the private Home Assistant webhook never ends up in the repository.
        buildConfigField("String", "DEFAULT_WEBHOOK_URL", "\"${localString("owntracks.url")}\"")
        buildConfigField("String", "DEFAULT_USER", "\"${localString("owntracks.user")}\"")
        buildConfigField("String", "DEFAULT_DEVICE", "\"${localString("owntracks.device")}\"")
        buildConfigField("String", "DEFAULT_TID", "\"${localString("owntracks.tid")}\"")
    }

    // Prefer the shared key in the home directory (the one used by all other
    // asdfghasdfgh apps). CI has no home key: it restores signing/ from repository
    // secrets instead. Signing debug builds with the same key means every install
    // can be updated later.
    val homeSigningDir = File(System.getProperty("user.home"), "asdfghasdfgh-signing")
    val homeSigningPropertiesFile = File(homeSigningDir, "asdfghasdfgh-keystore.properties")
    val projectSigningPropertiesFile = rootProject.file("signing/signing.properties")

    val realSigningConfig = when {
        homeSigningPropertiesFile.isFile -> {
            val signingProperties = Properties().apply {
                homeSigningPropertiesFile.inputStream().use(::load)
            }
            val storeName = signingProperties.getProperty("storeFile")
                ?: "asdfghasdfgh-release.keystore"
            val storeFile = File(storeName).let {
                if (it.isAbsolute) it else File(homeSigningDir, it.path)
            }
            if (storeFile.isFile) {
                signingConfigs.create("real") {
                    this.storeFile = storeFile
                    storePassword = signingProperties.getProperty("storePassword")
                    keyAlias = signingProperties.getProperty("keyAlias")
                    keyPassword = signingProperties.getProperty("keyPassword")
                }
            } else {
                null
            }
        }

        projectSigningPropertiesFile.isFile -> {
            val signingProperties = Properties().apply {
                projectSigningPropertiesFile.inputStream().use(::load)
            }
            signingConfigs.create("real") {
                storeFile = rootProject.file("signing/signing.keystore")
                storePassword = signingProperties.getProperty("STORE_PASSWORD")
                keyAlias = signingProperties.getProperty("KEY_ALIAS")
                keyPassword = signingProperties.getProperty("KEY_PASSWORD")
            }
        }

        else -> null
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            realSigningConfig?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            realSigningConfig?.let { signingConfig = it }
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
