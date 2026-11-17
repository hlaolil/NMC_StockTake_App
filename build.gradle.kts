// Versions for the whole project. Android Studio may offer newer ones; these
// are known to work together (Android Gradle Plugin 8.7 needs Gradle 8.9+,
// and Kotlin 2.0 brings its own Compose compiler plugin).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
