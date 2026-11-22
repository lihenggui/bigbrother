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

package li.mercury.bigbrother.core.data.repository

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FirstLaunchRepositoryTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun givenFreshInstall_whenActivitiesOpenConcurrently_thenOnlyOneShowsGuide() = runTest {
        setInstallTimes(10, 10)
        val results = List(2) { async { LocalFirstLaunchRepository(app, Dispatchers.IO).claim() } }.awaitAll()
        assertEquals(1, results.count { it })
        assertFalse(LocalFirstLaunchRepository(app, Dispatchers.IO).claim())
    }

    @Test
    fun givenUpgradeFromBeforeGuide_whenOpened_thenGoesStraightToOverview() = runTest {
        setInstallTimes(10, 20)
        assertFalse(LocalFirstLaunchRepository(app, Dispatchers.IO).claim())
    }

    @Test
    fun givenNewProfileOnUpdatedDevice_whenOpened_thenShowsGuide() = runTest {
        setInstallTimes(20, 10)
        assertTrue(LocalFirstLaunchRepository(app, Dispatchers.IO).claim())
    }

    @Test
    fun givenFreshInstall_whenFirstLaunchIsClaimed_thenItIsNoLongerUnclaimed() = runTest {
        setInstallTimes(10, 10)
        val repository = LocalFirstLaunchRepository(app, Dispatchers.IO)
        assertTrue(repository.isUnclaimed())
        repository.claim()
        assertFalse(repository.isUnclaimed())
    }

    private fun setInstallTimes(installed: Long, updated: Long) {
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).apply {
            firstInstallTime = installed
            lastUpdateTime = updated
        }
    }
}
