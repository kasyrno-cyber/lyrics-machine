pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo1.maven.org/maven2")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://repo1.maven.org/maven2")
    }
}

rootProject.name = "lyrics-machine"

include(":core")
