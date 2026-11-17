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
    alias(libs.plugins.bigbrother.android.feature.impl)
    alias(libs.plugins.bigbrother.android.library.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "li.mercury.bigbrother.feature.timeline.impl"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.feature.timeline.api)
    implementation(projects.feature.appdetail.api)

    implementation(libs.androidx.compose.material3.adaptive.navigation3)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(projects.core.screenshotTesting)
    testImplementation(projects.core.testing)
    // The timeline's top bar, in the screenshots.
    testImplementation(projects.feature.search.impl)
}
