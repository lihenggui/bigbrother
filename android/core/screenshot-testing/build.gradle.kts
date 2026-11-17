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
    alias(libs.plugins.bigbrother.android.library)
    alias(libs.plugins.bigbrother.android.library.compose)
}

android {
    namespace = "li.mercury.bigbrother.core.screenshottesting"
}

// What the modules' screenshot tests share, as in Blocker's
// core:screenshot-testing: the capture helpers, and the demo records and app
// icons the screenshots show. Modules use it as a test dependency only.
dependencies {
    api(libs.bundles.androidx.compose.ui.test)
    // The compose test rule idles through Espresso. The 3.5 it brings looks
    // for InputManager.getInstance(), which recent SDKs no longer have.
    api(libs.androidx.test.espresso.core)
    api(libs.roborazzi)
    api(libs.roborazzi.accessibility.check)
    api(projects.core.model)

    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.robolectric)
    implementation(projects.core.designsystem)
}
