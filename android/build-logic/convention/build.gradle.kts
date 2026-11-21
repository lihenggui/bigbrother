/*
 * Copyright (C) 2026 Mercury Li
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    `kotlin-dsl`
    alias(libs.plugins.spotless)
}

group = "li.mercury.bigbrother.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.licensee.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
}

// The convention plugins' own sources, formatted like the modules they configure.
spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(mapOf("android" to "true"))
        licenseHeaderFile(rootDir.resolve("../spotless/copyright.kt"))
        endWithNewline()
    }
    format("kts") {
        target("*.kts")
        licenseHeaderFile(rootDir.resolve("../spotless/copyright.kts"), "(^(?![\\/ ]\\*)(?!$).*$)")
        endWithNewline()
    }
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.bigbrother.android.application.asProvider().get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = libs.plugins.bigbrother.android.application.compose.get().pluginId
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.bigbrother.android.library.asProvider().get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = libs.plugins.bigbrother.android.library.compose.get().pluginId
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeatureApi") {
            id = libs.plugins.bigbrother.android.feature.api.get().pluginId
            implementationClass = "AndroidFeatureApiConventionPlugin"
        }
        register("androidFeatureImpl") {
            id = libs.plugins.bigbrother.android.feature.impl.get().pluginId
            implementationClass = "AndroidFeatureImplConventionPlugin"
        }
        register("androidLint") {
            id = libs.plugins.bigbrother.android.lint.get().pluginId
            implementationClass = "AndroidLintConventionPlugin"
        }
        register("androidHilt") {
            id = libs.plugins.bigbrother.android.hilt.get().pluginId
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("flashableModule") {
            id = libs.plugins.bigbrother.flashable.module.get().pluginId
            implementationClass = "FlashableModuleConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.bigbrother.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
