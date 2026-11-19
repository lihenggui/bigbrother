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
    namespace = "li.mercury.bigbrother.hook"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // The Xposed API is provided by the framework at run time.
    compileOnly(libs.libxposed.api)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.kotlin.test.junit)
}

// The tests read the protocol cases shared with the daemon's tests.
val protocolCases = rootDir.resolve("../testdata/protocol")
tasks.withType<Test>().configureEach {
    inputs.dir(protocolCases).withPropertyName("protocolCases").withPathSensitivity(PathSensitivity.RELATIVE)
}
