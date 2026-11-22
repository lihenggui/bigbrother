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
import android.os.StrictMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Claim the first launcher session, even if the process is killed before the guide is finished. */
interface FirstLaunchRepository {
    suspend fun claim(): Boolean

    /**
     * Whether no session has claimed the first launch yet. A single file check that may run on the main thread: the
     * splash screen's exit is decided before the activity resumes.
     */
    fun isUnclaimed(): Boolean
}

internal class LocalFirstLaunchRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : FirstLaunchRepository {
    private val marker get() = File(context.noBackupFilesDir, "first-launch")

    override suspend fun claim(): Boolean = withContext(ioDispatcher) {
        try {
            if (!marker.createNewFile()) return@withContext false
            // An installation upgraded from a release predating the marker has already had its introduction.
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime <= info.firstInstallTime
        } catch (_: IOException) {
            Timber.w("Could not save first-launch state")
            false
        } catch (_: PackageManager.NameNotFoundException) {
            Timber.w("Could not read the installation date")
            false
        }
    }

    override fun isUnclaimed(): Boolean {
        val policy = StrictMode.allowThreadDiskReads()
        return try {
            !marker.exists()
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }
}
