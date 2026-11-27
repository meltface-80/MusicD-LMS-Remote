import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Mallard: the native Android remote for MusicD LMS Remote. Ported from Mandarin Remote's Compose app; everything
 * Mandarin-only (Tailscale, phone playback, the USB DAC, downloads) is gone, so there is no native code and one APK
 * fits every phone.
 */
android {
    namespace = "com.musicd.mallard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.musicd.mallard"
        minSdk = 26
        targetSdk = 36
        // versionCode must rise with every published build or Android refuses to install over the previous one.
        // A dev test build (.github/workflows/mallard.yml) passes MALLARD_TEST_BUILD, the workflow's run number:
        // it is named 0.2.0-tN and coded base*1000+N, so each installs over the last, and the release of the same
        // version (base*1000+999) installs over all of them. Raise mallardBase with each Mallard release.
        val mallardBase = 2
        val testBuild = System.getenv("MALLARD_TEST_BUILD")?.toIntOrNull()?.takeIf { it in 1..998 }
        versionCode = mallardBase * 1000 + (testBuild ?: 999)
        versionName = "0.2.0" + (testBuild?.let { "-t$it" } ?: "")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    /*
     * Builds without the release secrets are signed with THIS key, committed beside this file, so every test build
     * installs over the one before (a fresh debug key per CI run makes Android refuse the update). It is a debug key
     * and public by design — it only lets sideloaded builds update in place. For a key nobody else holds, set the
     * MALLARD_KEYSTORE secrets (see .github/workflows/mallard.yml); an APK signed with one will not install over one
     * signed with the other.
     */
    signingConfigs.getByName("debug") {
        storeFile = file("mallard-debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }

    val keystorePath = System.getenv("MALLARD_KEYSTORE")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("MALLARD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MALLARD_KEY_ALIAS") ?: "mallard"
            keyPassword = System.getenv("MALLARD_KEY_PASSWORD") ?: System.getenv("MALLARD_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint { checkReleaseBuilds = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Everything that is not Android — the address rules, the API client, the models — lives in :core, unit-tested on a plain JVM.
    implementation(project(":core"))
    implementation("androidx.core:core:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
