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

package li.mercury.bigbrother.core.common.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier

/** The app's private files directory. */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class FilesDir

/** This app's package name, to leave its own lookups out of what is shown. */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class AppPackageName

/** The app's cache directory. */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class CacheDir

@Module
@InstallIn(SingletonComponent::class)
internal object SysModule {
    @Provides
    @FilesDir
    fun providesFilesDir(@ApplicationContext context: Context): File = context.filesDir

    @Provides
    @AppPackageName
    fun providesAppPackageName(@ApplicationContext context: Context): String = context.packageName

    @Provides
    @CacheDir
    fun providesCacheDir(@ApplicationContext context: Context): File = context.cacheDir
}
