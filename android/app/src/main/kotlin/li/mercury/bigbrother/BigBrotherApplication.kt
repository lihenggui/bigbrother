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

package li.mercury.bigbrother

import android.app.Application
import android.content.pm.ApplicationInfo
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import li.mercury.bigbrother.core.common.logging.ReleaseTree
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class BigBrotherApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var releaseTree: ReleaseTree

    // dagger.Lazy: created when Coil first needs it, not at startup.
    @Inject
    lateinit var imageLoader: Lazy<ImageLoader>

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Timber.plant(Timber.DebugTree())
        Timber.plant(releaseTree)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()
}
