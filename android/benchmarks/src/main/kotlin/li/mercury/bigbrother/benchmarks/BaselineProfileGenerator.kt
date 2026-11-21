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

package li.mercury.bigbrother.benchmarks

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()

    @Test
    fun givenAppClosed_whenLaunching_thenCaptureStartupProfile() = profile.collect(
        packageName = APP_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        BigBrotherJourney(this).launch()
    }

    @Test
    fun givenAppClosed_whenNavigatingAndScrolling_thenCaptureBaselineProfile() = profile.collect(
        packageName = APP_PACKAGE,
    ) {
        pressHome()
        BigBrotherJourney(this).apply {
            launch()
            visitDestinationsAndScroll()
        }
    }
}
