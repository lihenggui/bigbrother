enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // libsu is published through JitPack only.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.topjohnwu.libsu") }
        }
    }
}

rootProject.name = "big-brother"
include(":app")
include(":core:common")
include(":core:data")
include(":core:designsystem")
include(":core:model")
include(":core:navigation")
include(":core:ui")
include(":feature:applist:api")
include(":feature:applist:impl")
include(":feature:appdetail:api")
include(":feature:appdetail:impl")
include(":hook")
