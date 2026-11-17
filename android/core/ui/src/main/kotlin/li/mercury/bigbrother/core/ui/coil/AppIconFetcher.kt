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
import android.content.pm.PackageInfo
import androidx.annotation.Px
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import me.zhanghai.android.appiconloader.AppIconLoader

/**
 * Loads an app's icon for Coil from its [PackageInfo], drawn the way a
 * launcher draws it. Coil runs this off the main thread and keeps the result
 * in memory, keyed by [AppIconKeyer].
 */
class AppIconFetcher private constructor(
    private val loader: AppIconLoader,
    private val applicationInfo: ApplicationInfo,
) : Fetcher {
    override suspend fun fetch(): FetchResult = ImageFetchResult(
        image = loader.loadIcon(applicationInfo).asImage(),
        isSampled = true,
        dataSource = DataSource.DISK,
    )

    /**
     * @param iconSize the size icons are drawn at, in pixels.
     * @param shrinkNonAdaptiveIcons whether to fit legacy icons into the
     *   adaptive icon shape, as launchers do.
     */
    class Factory(
        @Px iconSize: Int,
        shrinkNonAdaptiveIcons: Boolean,
        context: Context,
    ) : Fetcher.Factory<PackageInfo> {
        private val loader = AppIconLoader(iconSize, shrinkNonAdaptiveIcons, context.applicationContext)

        override fun create(data: PackageInfo, options: Options, imageLoader: ImageLoader): Fetcher? {
            val applicationInfo = data.applicationInfo ?: return null
            return AppIconFetcher(loader, applicationInfo)
        }
    }
}
