pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "listen-together"

include(
    ":protocol",
    ":backend",
    ":core:model",
    ":core:common",
    ":core:network",
    ":core:data",
    ":core:designsystem",
    ":music:api",
    ":music:youtube",
    ":playback",
    ":sync",
    ":voice",
    ":communication",
    ":audio",
    ":service",
    ":ui",
    ":testing",
    ":app",
)
