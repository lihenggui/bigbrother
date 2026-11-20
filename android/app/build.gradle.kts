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

import java.util.Properties

plugins {
    alias(libs.plugins.bigbrother.android.application)
    alias(libs.plugins.bigbrother.android.application.compose)
    alias(libs.plugins.bigbrother.android.hilt)
    alias(libs.plugins.bigbrother.flashable.module)
    alias(libs.plugins.dependencyGuard)
    alias(libs.plugins.roborazzi)
}

// Release signing is configured in keystore.properties (not in version
// control), with the keys storeFile, storePassword, keyAlias and keyPassword.
val keystoreProperties = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "li.mercury.bigbrother"

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "li.mercury.bigbrother"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Without a release key the build is signed with the debug key,
            // which is enough to install it but differs between machines.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    androidResources {
        // Locales the app is translated into; drives the per-app language picker.
        generateLocaleConfig = true
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(projects.feature.applist.api)
    implementation(projects.feature.applist.impl)
    implementation(projects.feature.appdetail.api)
    implementation(projects.feature.appdetail.impl)
    implementation(projects.feature.jail.api)
    implementation(projects.feature.jail.impl)
    implementation(projects.feature.overview.api)
    implementation(projects.feature.overview.impl)
    implementation(projects.feature.timeline.api)
    implementation(projects.feature.timeline.impl)
    implementation(projects.feature.search.impl)
    implementation(projects.feature.settings.api)
    implementation(projects.feature.settings.impl)

    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.navigation)
    implementation(projects.core.ui)

    // The LSPosed hook ships in the same APK.
    implementation(projects.hook)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.compose.material3.adaptive.navigationSuite)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.coil)
    implementation(libs.androidx.lifecycle.viewModel.navigation3)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigationEvent)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.robolectric)
    testImplementation(projects.core.screenshotTesting)
    testImplementation(projects.core.testing)
}

// Every library in the release APK, checked in (app/dependencies/): a new
// one, direct or transitive, comes in with a reviewed change.
dependencyGuard {
    configuration("releaseRuntimeClasspath")
}

// The tests read the protocol cases shared with the daemon's tests, and the
// permission matrix shared with scripts/check-permissions.sh.
val protocolCases = rootDir.resolve("../testdata/protocol")
val permissionMatrix = rootDir.resolve("../testdata/permissions/matrix.tsv")
tasks.withType<Test>().configureEach {
    inputs.dir(protocolCases).withPropertyName("protocolCases").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(permissionMatrix).withPropertyName("permissionMatrix").withPathSensitivity(PathSensitivity.RELATIVE)
}
