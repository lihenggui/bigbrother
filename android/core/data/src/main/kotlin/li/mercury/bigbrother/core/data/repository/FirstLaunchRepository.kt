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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Claim the first launcher session, even if the process is killed before the guide is finished. */
fun interface FirstLaunchRepository {
    suspend fun claim(): Boolean
}

internal class LocalFirstLaunchRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : FirstLaunchRepository {
    override suspend fun claim(): Boolean = withContext(ioDispatcher) {
        try {
            if (!File(context.noBackupFilesDir, "first-launch").createNewFile()) return@withContext false
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
}
