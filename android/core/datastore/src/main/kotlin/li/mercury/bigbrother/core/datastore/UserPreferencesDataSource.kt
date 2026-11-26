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

package li.mercury.bigbrother.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import li.mercury.bigbrother.core.model.ThemeMode
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData
import java.io.IOException
import javax.inject.Inject

/** The user's settings, stored on the device. */
class UserPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val userData: Flow<UserData> = dataStore.data
        // A file that can't be read is treated as unset rather than failing
        // every screen that follows the settings.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { preferences ->
            val defaults = UserData()
            UserData(
                timeRange = preferences[TIME_RANGE_HOURS]?.let(TimeRange::fromHours) ?: defaults.timeRange,
                showSystemApps = preferences[SHOW_SYSTEM_APPS] ?: defaults.showSystemApps,
                useDynamicColor = preferences[USE_DYNAMIC_COLOR] ?: defaults.useDynamicColor,
                themeMode = ThemeMode.entries.find { it.name == preferences[THEME_MODE] } ?: defaults.themeMode,
            )
        }

    suspend fun setTimeRange(range: TimeRange) {
        dataStore.edit { it[TIME_RANGE_HOURS] = range.hours }
    }

    suspend fun setShowSystemApps(show: Boolean) {
        dataStore.edit { it[SHOW_SYSTEM_APPS] = show }
    }

    suspend fun setUseDynamicColor(use: Boolean) {
        dataStore.edit { it[USE_DYNAMIC_COLOR] = use }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    private companion object {
        val TIME_RANGE_HOURS = intPreferencesKey("time_range_hours")
        val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
