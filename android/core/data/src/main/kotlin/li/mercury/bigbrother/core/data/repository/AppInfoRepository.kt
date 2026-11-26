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
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.LocaleList
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import li.mercury.bigbrother.core.model.AppIconSource
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.PackageDetails
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
 * Backed by the package manager. The cache retains recent hits and misses
 * for the current locale. Package details are looked up each time.
 */
@Singleton
internal class PackageManagerAppInfoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : AppInfoRepository {
    private val packageManager = context.packageManager
    private val cache = LinkedHashMap<String, CachedApp>(CAPACITY, 0.75f, true)

    override suspend fun getAppInfo(packageName: String): AppInfo? = withContext(ioDispatcher) {
        cachedOrLoad(packageName, context.resources.configuration.locales)
    }

    override suspend fun getAppInfo(packageNames: Collection<String>): Map<String, AppInfo> = withContext(ioDispatcher) {
        val locales = context.resources.configuration.locales
        buildMap {
            for (name in packageNames) {
                cachedOrLoad(name, locales)?.let { put(name, it) }
            }
        }
    }

    private fun cachedOrLoad(packageName: String, locales: LocaleList): AppInfo? {
        synchronized(cache) {
            cache[packageName]?.let { entry ->
                if (entry.locales == locales && SystemClock.elapsedRealtime() < entry.expiresAt) return entry.info
            }
        }
        val info = load(packageName)
        val maxAge = if (info == null) MISSING_TTL_MS else INSTALLED_TTL_MS
        synchronized(cache) {
            cache[packageName] = CachedApp(info, locales, SystemClock.elapsedRealtime() + maxAge)
            if (cache.size > CAPACITY) cache.remove(cache.keys.first())
        }
        return info
    }

    private fun load(packageName: String): AppInfo? = try {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val info = packageInfo.applicationInfo ?: return null
        packageManager.toAppInfo(packageInfo, info)
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

    private data class CachedApp(val info: AppInfo?, val locales: LocaleList, val expiresAt: Long)

    private companion object {
        const val CAPACITY = 1_024
        const val MISSING_TTL_MS = 30_000L
        const val INSTALLED_TTL_MS = 60_000L
    }
}

internal fun PackageManager.toAppInfo(packageInfo: PackageInfo, applicationInfo: ApplicationInfo): AppInfo = AppInfo(
    label = getApplicationLabel(applicationInfo).toString(),
    iconSource = AppIconSource(packageInfo.packageName, applicationInfo.uid, packageInfo.lastUpdateTime),
    isSystem = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
)
