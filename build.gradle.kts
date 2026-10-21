// Plugin classpath is declared here (not via the plugins block) so the Android plugins can be
// omitted when no Android SDK is available (see settings.gradle.kts). Versions mirror libs.versions.toml.
buildscript {
    repositories {
        google()
        maven("https://repo1.maven.org/maven2")
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
        if (file("local.properties").exists() || System.getenv("ANDROID_HOME") != null) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.20")
        }
    }
}
