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

import android.content.pm.ApplicationInfo
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.PackageDetails
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
// The SDKs CI provides offline are 24 and 37 (android/build.gradle.kts);
// these use package and app op APIs newer than 24.
@Config(sdk = [37])
class PackageManagerAppInfoRepositoryTest {
    private val app = RuntimeEnvironment.getApplication()
    private val packages = shadowOf(app.packageManager)

    @Test
    fun givenKnownAndMissingPackages_whenReadInBulk_thenReturnsOnlyInstalledAppsAndCachesLabels() = runTest {
        packages.installPackage(testPackage(NOTES, 10101, "Notes"))
        val repository = PackageManagerAppInfoRepository(app, StandardTestDispatcher(testScheduler))

        packages.installPackage(testPackage(MEDIA, 10010, "Media", ApplicationInfo.FLAG_SYSTEM))
        val result = repository.getAppInfo(listOf(NOTES, MISSING, MEDIA))

        assertEquals(setOf(NOTES, MEDIA), result.keys)
        assertEquals("Notes", result.getValue(NOTES).label)
        assertFalse(result.getValue(NOTES).isSystem)
        assertTrue(result.getValue(MEDIA).isSystem)

        packages.removePackage(NOTES)
        assertEquals(result[NOTES], repository.getAppInfo(NOTES))
    }

    @Test
    fun givenInstalledApp_whenPackageIsUpdated_thenDetailsReflectTheNewVersion() = runTest {
        packages.installPackage(
            testPackage(NOTES, 10101, "Notes").apply {
                versionName = "1.0"
                setLongVersionCode(10)
                firstInstallTime = 1_000
                lastUpdateTime = 2_000
            },
        )
        val repository = PackageManagerAppInfoRepository(app, StandardTestDispatcher(testScheduler))

        val info = assertNotNull(repository.getAppInfo(NOTES))
        assertEquals(AppIconSource(NOTES, 10101, lastUpdateTime = 2_000), info.iconSource)
        assertEquals(PackageDetails("1.0", 10, 35, 28, 1_000, 2_000), repository.getPackageDetails(NOTES))

        packages.getInternalMutablePackageInfo(NOTES).apply {
            versionName = "2.0"
            setLongVersionCode(20)
            lastUpdateTime = 3_000
        }

        assertEquals(PackageDetails("2.0", 20, 35, 28, 1_000, 3_000), repository.getPackageDetails(NOTES))
        assertNull(repository.getPackageDetails(MISSING))
    }

    @Test
    fun givenMissingPackage_whenInstalled_thenFoundAfterNegativeCacheExpires() = runTest {
        val repository = PackageManagerAppInfoRepository(app, StandardTestDispatcher(testScheduler))
        assertNull(repository.getAppInfo(MEDIA))

        packages.installPackage(testPackage(MEDIA, 10010, "Media"))
        assertNull(repository.getAppInfo(MEDIA))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))

        assertEquals("Media", repository.getAppInfo(MEDIA)?.label)
    }

    @Test
    fun givenCachedLabel_whenLocaleChanges_thenLoadsUpdatedLabelAndIconKey() = runTest {
        packages.installPackage(testPackage(NOTES, 10101, "Notes"))
        val repository = PackageManagerAppInfoRepository(app, StandardTestDispatcher(testScheduler))
        assertEquals("Notes", repository.getAppInfo(NOTES)?.label)
        packages.getInternalMutablePackageInfo(NOTES).apply {
            applicationInfo?.nonLocalizedLabel = "Notas"
            lastUpdateTime = 2_000
        }

        RuntimeEnvironment.setQualifiers("+es-rES")

        val info = assertNotNull(repository.getAppInfo(NOTES))
        assertEquals("Notas", info.label)
        assertEquals(2_000L, info.iconSource.lastUpdateTime)
    }

    private companion object {
        const val NOTES = "com.example.notes"
        const val MEDIA = "com.example.media"
        const val MISSING = "com.example.missing"
    }
}
