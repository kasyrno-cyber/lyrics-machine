// Plugin classpath is declared here (not via the plugins block) so the Android plugins can be
// omitted when no Android SDK is available (see settings.gradle.kts). Versions come from libs.versions.toml.
buildscript {
    val hasAndroidSdk = gradle.extra["hasAndroidSdk"] as Boolean
    repositories {
        google()
        maven("https://repo1.maven.org/maven2")
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        if (hasAndroidSdk) {
            classpath(libs.android.gradle.plugin)
            classpath(libs.compose.compiler.gradle.plugin)
            classpath(libs.ksp.gradle.plugin)
        }
    }
}
