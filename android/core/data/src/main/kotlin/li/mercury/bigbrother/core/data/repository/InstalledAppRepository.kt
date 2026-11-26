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

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.LoadFailure
import javax.inject.Inject

/** The apps installed for the calling user, whether or not they left records. */
interface InstalledAppRepository {
    /** Throws [RecordsException] if Android cannot supply the installed apps. */
    suspend fun getInstalledApps(): List<InstalledApp>

    /** The live uid for a single package, or null if it is not installed for the calling user. */
    suspend fun getInstalledUid(packageName: String): Int?

    /**
     * The permissions the app's manifest names: those it asks for, and
     * those its services are bound with (a notification listener's, an
     * accessibility service's); null if it is not installed for the calling
     * user. The manifest is the same for every user the app is installed
     * for.
     */
    suspend fun getRequestedPermissions(packageName: String): Set<String>?
}

internal class PackageManagerInstalledAppRepository @Inject constructor(
    @ApplicationContext context: Context,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : InstalledAppRepository {
    private val packageManager = context.packageManager

    override suspend fun getInstalledApps(): List<InstalledApp> = withContext(ioDispatcher) {
        readPackageManager { readInstalledApps() }
    }

    override suspend fun getInstalledUid(packageName: String): Int? = withContext(ioDispatcher) {
        readPackageManager {
            try {
                packageManager.getPackageUid(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    private inline fun <T> readPackageManager(read: () -> T): T = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        // PackageManager rethrows Binder failures as runtime exceptions.
        throw RecordsException.CommandFailed(LoadFailure.Operation.INSTALLED_APPS_UNAVAILABLE)
    }

    private fun readInstalledApps(): List<InstalledApp> = packageManager.getInstalledPackages(0).mapNotNull { packageInfo ->
        val info = packageInfo.applicationInfo ?: return@mapNotNull null
        InstalledApp(
            packageName = packageInfo.packageName,
            uid = info.uid,
            info = packageManager.toAppInfo(packageInfo, info),
        )
    }

    override suspend fun getRequestedPermissions(packageName: String): Set<String>? = withContext(ioDispatcher) {
        try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
            info.requestedPermissions.orEmpty().toSet() + info.services.orEmpty().mapNotNull { it.permission }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
