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

package li.mercury.bigbrother.core.ui.coil

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.os.UserHandle
import androidx.annotation.Px
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import li.mercury.bigbrother.core.model.AppIconSource
import me.zhanghai.android.appiconloader.AppIconLoader

/**
 * Draws an app's icon the way a launcher does. The package lookup happens
 * in [fetch] so a Coil memory cache hit avoids calling the package manager.
 */
class AppIconFetcher private constructor(
    private val loader: AppIconLoader,
    private val context: Context,
    private val source: AppIconSource,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val applicationInfo = checkNotNull(resolveApplicationInfo()?.takeIf { it.uid == source.uid }) {
            "App icon unavailable"
        }
        return ImageFetchResult(
            image = loader.loadIcon(applicationInfo).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun resolveApplicationInfo(): ApplicationInfo? = try {
        val user = UserHandle.getUserHandleForUid(source.uid)
        if (user == Process.myUserHandle()) {
            context.packageManager.getApplicationInfo(source.packageName, 0)
        } else {
            context.getSystemService(LauncherApps::class.java)?.getApplicationInfo(source.packageName, 0, user)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /**
     * @param iconSize the size icons are drawn at, in pixels.
     * @param shrinkNonAdaptiveIcons whether to fit legacy icons into the
     *   adaptive icon shape, as launchers do.
     */
    class Factory(
        @Px iconSize: Int,
        shrinkNonAdaptiveIcons: Boolean,
        context: Context,
    ) : Fetcher.Factory<AppIconSource> {
        private val context = context.applicationContext
        private val loader = AppIconLoader(iconSize, shrinkNonAdaptiveIcons, this.context)

        override fun create(data: AppIconSource, options: Options, imageLoader: ImageLoader): Fetcher = AppIconFetcher(loader, context, data)
    }
}
