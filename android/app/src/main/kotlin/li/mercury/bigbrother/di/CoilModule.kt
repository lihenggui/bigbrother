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

package li.mercury.bigbrother.di

import android.content.Context
import coil3.ImageLoader
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import li.mercury.bigbrother.core.ui.coil.AppIconFetcher
import li.mercury.bigbrother.core.ui.coil.AppIconKeyer
import javax.inject.Singleton
import li.mercury.bigbrother.core.ui.R as UiR

@Module
@InstallIn(SingletonComponent::class)
object CoilModule {
    /**
     * Coil's image loader, which the app uses for app icons only: they come
     * from the package manager, never the network. Coil obtains it through
     * BigBrotherApplication's SingletonImageLoader.Factory.
     */
    @Provides
    @Singleton
    fun imageLoader(@ApplicationContext context: Context): ImageLoader {
        val iconSize = context.resources.getDimensionPixelSize(UiR.dimen.app_icon_size)
        return ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory(iconSize, true, context))
            }
            // An icon loaded from the package manager fades in rather than
            // popping in; one already in memory is drawn at once, without it.
            .crossfade(true)
            .build()
    }
}
