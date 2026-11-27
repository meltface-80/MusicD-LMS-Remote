// Plugin versions are declared once, here, and applied in the modules that
// need them — the same arrangement as Android Random Remote.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    // Compose (the native screens, old/plan-android-native.md): its compiler is Kotlin's own.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
