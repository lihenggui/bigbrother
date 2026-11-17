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

import app.cash.licensee.LicenseeExtension
import app.cash.licensee.UnusedAction
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Fails the build on a dependency whose license is not allowed. The app is
 * GPL-3.0, so only licenses compatible with it are: unlike Blocker, no
 * EPL-2.0.
 */
internal fun Project.configureLicensee() {
    pluginManager.apply("app.cash.licensee")
    configure<LicenseeExtension> {
        allow("Apache-2.0")
        allow("MIT")
        allow("BSD-2-Clause")
        allow("BSD-3-Clause")
        allowUrl("https://opensource.org/licenses/MIT")
        allowUrl("https://opensource.org/license/mit")
        allowUrl("http://opensource.org/licenses/BSD-2-Clause")
        // The Android SDK's own artifacts.
        allowUrl("https://developer.android.com/studio/terms.html")
        // Published through JitPack without license metadata; Apache-2.0
        // (https://github.com/topjohnwu/libsu/blob/master/LICENSE).
        ignoreDependencies("com.github.topjohnwu.libsu") {
            because("Apache-2.0")
        }
        unusedAction(UnusedAction.IGNORE)
    }
}
