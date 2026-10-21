pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        maven("https://repo1.maven.org/maven2")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://repo1.maven.org/maven2")
    }
}

rootProject.name = "lyrics-machine"

include(":core")
// :app needs an Android SDK; include it only when one is available (CI sets ANDROID_HOME) so :core:test runs anywhere.
if (System.getenv("ANDROID_HOME") != null || file("local.properties").exists()) {
    include(":app")
}
