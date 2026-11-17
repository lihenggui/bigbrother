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
}

android {
    namespace = "li.mercury.bigbrother.core.testing"
}

// Test doubles and rules shared by the modules' unit tests, as in Blocker's
// core:testing.
dependencies {
    api(projects.core.data)
    api(libs.kotlin.test.junit)
    api(libs.kotlinx.coroutines.test)
}
