pluginManagement {
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
    }
}

rootProject.name = "S0undPlus"

include(":app")

// All Twitch protocol logic lives in a plain-JVM build so it can be compiled and unit-tested
// without an Android SDK (`./gradlew -p twitch-core test`). The app consumes it through
// automatic composite-build substitution of `com.s0undplus:twitch-core`.
includeBuild("twitch-core")
