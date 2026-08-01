// Suno Local Player — settings.gradle.kts
// Root project settings for the Suno local playlist downloader & player MVP.
// Configures plugin and dependency repositories, and includes the :app module.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "suno-local-player"
include(":app")
