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
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.dependencyGuard) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
}

// Resolve the versions separately so dependency conflict resolution keeps both.
val robolectricSdk24 = configurations.resolvable("robolectricSdk24") {
    isTransitive = false
}
val robolectricSdk37 = configurations.resolvable("robolectricSdk37") {
    isTransitive = false
}
dependencies {
    add(robolectricSdk24.name, libs.robolectricAndroid24)
    add(robolectricSdk37.name, libs.robolectricAndroid37)
}

// Gradle caches the SDKs alongside the other test dependencies, so test workers
// can use them without making their own Maven requests.
val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdk")
val prepareRobolectricSdk = tasks.register<Sync>("prepareRobolectricSdk") {
    from(robolectricSdk24, robolectricSdk37)
    into(robolectricSdkDirectory)
}
if (providers.environmentVariable("CI").orNull == "true") {
    val sdkDirectory = robolectricSdkDirectory.get().asFile
    subprojects {
        tasks.withType<Test>().configureEach {
            dependsOn(prepareRobolectricSdk)
            inputs.dir(sdkDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
            systemProperty("robolectric.dependency.dir", sdkDirectory.absolutePath)
            systemProperty("robolectric.offline", "true")
        }
    }
}

// This build's own scripts and build-logic's settings; each module's
// sources are covered by its convention plugin (build-logic: Spotless.kt).
spotless {
    format("kts") {
        target("*.kts", "build-logic/*.kts")
        licenseHeaderFile(rootDir.resolve("spotless/copyright.kts"), "(^(?![\\/ ]\\*)(?!$).*$)")
        endWithNewline()
    }
}
