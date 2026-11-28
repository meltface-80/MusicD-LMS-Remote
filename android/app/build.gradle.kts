import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * Mallard: the native Android remote for MusicD LMS Remote. Ported from Mandarin Remote's Compose app. Its native parts
 * are for 64-bit ARM phones only, as Mandarin's are: the phone's Tailscale engine (libmusicdnet.so, built by
 * .github/workflows/mallard.yml), the USB audio driver and libopus under Media3's Opus decoder (src/main/cpp, built here
 * with the NDK and CMake the workflow installs).
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
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }

    // The native code (src/main/cpp, Mandarin's): the USB audio driver, and libopus under Media3's Opus decoder.
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    /*
     * THIS KEY IS PUBLIC (committed beside this file, password "android") and is for a pull request's build only, which
     * is never published. Every build that may be published is signed with the owner's private key
     * (.github/workflows/mallard.yml refuses otherwise): Play Protect blocked 0.2.0-t32, signed with this key and asking
     * to install updates, as harmful — anyone can sign with a public key, so an app signed with one that installs APKs
     * is indistinguishable from malware. An APK signed with one key will not install over one signed with the other.
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
        // The phone's Tailscale engine (libmusicdnet.so, PhoneEngine.kt) is RUN, not loaded: Android runs files only from
        // the extracted native-library folder, so it must be unpacked on install.
        jniLibs.useLegacyPackaging = true
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
    // The phone's own player away from home (PhoneStreamService): the server's Ogg Opus / FLAC stream, played by ExoPlayer.
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    // Media3's Opus decoder module, carried in src/main/java/androidx/media3/decoder/opus over the app's own libopus
    // (float out: Android's own gives 16-bit), builds on Media3's decoder base classes.
    implementation("androidx.media3:media3-decoder:1.8.0")
    implementation("androidx.media3:media3-extractor:1.8.0")
    // This phone's cache of tracks kept ahead (StreamCache, Mandarin's): its index in a database.
    implementation("androidx.media3:media3-database:1.8.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
