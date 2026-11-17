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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.PackageDetails
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Names, icons and package facts of installed apps, for the calling user. */
interface AppInfoRepository {
    /** Returns null for packages that are not installed. */
    suspend fun getAppInfo(packageName: String): AppInfo?

    /** [getAppInfo] for several packages; those not installed are omitted. */
    suspend fun getAppInfo(packageNames: Collection<String>): Map<String, AppInfo>

    /**
     * Version and install times; null for packages that are not installed.
     * Not cached, since they change whenever the app is updated.
     */
    suspend fun getPackageDetails(packageName: String): PackageDetails?
}

/**
 * Backed by the package manager. Names and icons are cached for the process
 * lifetime; package details are looked up each time.
 */
@Singleton
internal class PackageManagerAppInfoRepository @Inject constructor(
    @ApplicationContext context: Context,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : AppInfoRepository {
    private val packageManager = context.packageManager
    private val cache = ConcurrentHashMap<String, AppInfo>()

    override suspend fun getAppInfo(packageName: String): AppInfo? = cache[packageName] ?: withContext(ioDispatcher) { load(packageName) }

    override suspend fun getAppInfo(packageNames: Collection<String>): Map<String, AppInfo> {
        val missing = packageNames.filterNot { cache.containsKey(it) }
        // One trip to the IO threads for all of them, not one per app.
        if (missing.isNotEmpty()) withContext(ioDispatcher) { missing.forEach(::load) }
        return packageNames.mapNotNull { name -> cache[name]?.let { name to it } }.toMap()
    }

    private fun load(packageName: String): AppInfo? = try {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val info = packageInfo.applicationInfo ?: return null
        AppInfo(
            label = packageManager.getApplicationLabel(info).toString(),
            packageInfo = packageInfo,
            isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        ).also { cache[packageName] = it }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override suspend fun getPackageDetails(packageName: String): PackageDetails? = withContext(ioDispatcher) {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            PackageDetails(
                versionName = info.versionName,
                versionCode = info.longVersionCode,
                targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
                minSdk = info.applicationInfo?.minSdkVersion ?: 0,
                firstInstallTime = info.firstInstallTime,
                lastUpdateTime = info.lastUpdateTime,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
