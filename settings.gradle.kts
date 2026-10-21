pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeModule("com.github.vitaviva", "ComposeWaveLoading") }
        }
    }
}

rootProject.name = "atlas-launcher"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:data")
include(":core:memory")
include(":core:security")
include(":core:chat-crypto")
include(":core:chat-storage")
include(":core:chat-pairing")
include(":core:chat-remote")
include(":core:ui")
include(":feature:settings")
include(":feature:updater")
include(":feature:chat")
include(":feature:notes")
include(":feature:connect")


include(":athena:core")
include(":athena:runtime")
include(":athena:connector-weather")
include(":athena:connector-status")
include(":athena:gateway")
include(":core:athena-integration")
