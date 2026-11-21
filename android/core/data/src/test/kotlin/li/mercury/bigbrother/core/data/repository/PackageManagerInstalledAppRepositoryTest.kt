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

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.model.AppIconSource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
// The SDKs CI provides offline are 24 and 37 (android/build.gradle.kts);
// these use package and app op APIs newer than 24.
@Config(sdk = [37])
class PackageManagerInstalledAppRepositoryTest {
    private val app = RuntimeEnvironment.getApplication()
    private val packages = shadowOf(app.packageManager)

    @Test
    fun givenUserAndSystemApps_whenListed_thenPreservesUidsLabelsAndSystemFlags() = runTest {
        packages.installPackage(testPackage(NOTES, 10101, "Notes").apply { lastUpdateTime = 2_000 })
        packages.installPackage(testPackage(MEDIA, 10010, "Media", ApplicationInfo.FLAG_SYSTEM))
        val repository = PackageManagerInstalledAppRepository(app, StandardTestDispatcher(testScheduler))

        val installed = repository.getInstalledApps().associateBy { it.packageName }
        val notes = installed.getValue(NOTES)
        assertEquals(10101, notes.uid)
        assertEquals("Notes", notes.info.label)
        assertEquals(AppIconSource(NOTES, 10101, lastUpdateTime = 2_000), notes.info.iconSource)
        assertFalse(notes.info.isSystem)
        val media = installed.getValue(MEDIA)
        assertEquals(10010, media.uid)
        assertEquals("Media", media.info.label)
        assertTrue(media.info.isSystem)
    }

    @Test
    fun givenManifestWithServicePermissions_whenRead_thenCombinesAndDeduplicatesBothKinds() = runTest {
        packages.installPackage(
            testPackage(NOTES, 10101, "Notes").apply {
                requestedPermissions = arrayOf(ACCESS_FINE_LOCATION, BIND_ACCESSIBILITY_SERVICE)
                services = arrayOf(
                    service("Accessibility", BIND_ACCESSIBILITY_SERVICE),
                    service("Listener", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
                    service("Unrestricted", null),
                )
            },
        )
        packages.installPackage(testPackage(MEDIA, 10010, "Media"))
        val repository = PackageManagerInstalledAppRepository(app, StandardTestDispatcher(testScheduler))

        assertEquals(
            setOf(ACCESS_FINE_LOCATION, BIND_ACCESSIBILITY_SERVICE, "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"),
            repository.getRequestedPermissions(NOTES),
        )
        assertEquals(emptySet(), repository.getRequestedPermissions(MEDIA))
        assertNull(repository.getRequestedPermissions("com.example.missing"))
    }

    private fun service(name: String, requiredPermission: String?) = ServiceInfo().apply {
        packageName = NOTES
        this.name = "$NOTES.$name"
        permission = requiredPermission
    }

    private companion object {
        const val NOTES = "com.example.notes"
        const val MEDIA = "com.example.media"
    }
}
