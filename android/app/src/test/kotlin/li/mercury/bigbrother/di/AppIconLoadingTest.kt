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

package li.mercury.bigbrother.di

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.PER_USER_RANGE
import li.mercury.bigbrother.core.ui.coil.AppIconFetcher
import li.mercury.bigbrother.core.ui.coil.AppIconKeyer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppIconLoadingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val source = AppIconSource(context.packageName, context.applicationInfo.uid, lastUpdateTime = 1)

    @Test
    fun givenIconSource_whenKeyed_thenPackageUserAndUpdateTimeIdentifyTheIcon() {
        val keyer = AppIconKeyer()
        val options = Options(context)
        val key = keyer.key(source, options)

        assertEquals(key, keyer.key(source.copy(), options))
        assertNotEquals(key, keyer.key(source.copy(packageName = "com.example.other"), options))
        assertNotEquals(key, keyer.key(source.copy(uid = source.uid + PER_USER_RANGE), options))
        assertNotEquals(key, keyer.key(source.copy(lastUpdateTime = 2), options))
    }

    @Test
    fun givenCurrentUserApp_whenFetched_thenLoadsTheIcon() = runTest {
        assertIs<ImageFetchResult>(fetcher(source).fetch())
    }

    @Test
    fun givenOtherProfileApp_whenFetched_thenUsesThatProfilesApplicationInfo() = runTest {
        val profileSource = source.copy(uid = source.uid + PER_USER_RANGE)
        val user = UserHandle.getUserHandleForUid(profileSource.uid)
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val applicationInfo = ApplicationInfo(context.applicationInfo).apply { uid = profileSource.uid }
        shadowOf(launcherApps).addApplicationInfo(user, profileSource.packageName, applicationInfo)

        assertIs<ImageFetchResult>(fetcher(profileSource).fetch())
    }

    @Test
    fun givenMissingPackage_whenFetched_thenFailsAfterCreatingTheFetcher() = runTest {
        val fetcher = fetcher(source.copy(packageName = "com.example.missing"))

        assertFailsWith<IllegalStateException> { fetcher.fetch() }
    }

    private fun fetcher(source: AppIconSource) = AppIconFetcher.Factory(48, true, context)
        .create(source, Options(context), ImageLoader.Builder(context).build())
}
