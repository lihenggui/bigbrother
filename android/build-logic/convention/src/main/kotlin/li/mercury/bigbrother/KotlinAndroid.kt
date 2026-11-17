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

package li.mercury.bigbrother

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

/** SDK levels and language level shared by every Android module. */
internal fun configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = 37
        defaultConfig.apply {
            // The module requires a GKI 2.0 kernel, which ships with Android 12.
            minSdk = 31
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        // Unit tests run on the JVM: Android calls such as SystemClock and
        // Log return defaults there instead of failing.
        testOptions.unitTests.isReturnDefaultValues = true
    }
}
