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

import kotlinx.coroutines.flow.Flow
import li.mercury.bigbrother.core.datastore.UserPreferencesDataSource
import li.mercury.bigbrother.core.model.ThemeMode
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData
import java.io.IOException
import javax.inject.Inject

/** The user's settings. Setters throw [IOException] if the value cannot be saved. */
interface UserDataRepository {
    val userData: Flow<UserData>

    suspend fun setTimeRange(range: TimeRange)

    suspend fun setShowSystemApps(show: Boolean)

    suspend fun setUseDynamicColor(use: Boolean)

    suspend fun setThemeMode(mode: ThemeMode)
}

internal class DataStoreUserDataRepository @Inject constructor(
    private val preferences: UserPreferencesDataSource,
) : UserDataRepository {
    override val userData: Flow<UserData> = preferences.userData

    override suspend fun setTimeRange(range: TimeRange) = preferences.setTimeRange(range)

    override suspend fun setShowSystemApps(show: Boolean) = preferences.setShowSystemApps(show)

    override suspend fun setUseDynamicColor(use: Boolean) = preferences.setUseDynamicColor(use)

    override suspend fun setThemeMode(mode: ThemeMode) = preferences.setThemeMode(mode)
}
