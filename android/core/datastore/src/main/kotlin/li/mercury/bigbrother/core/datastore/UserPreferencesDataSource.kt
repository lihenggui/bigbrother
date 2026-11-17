package li.mercury.bigbrother.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData

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

    private companion object {
        val TIME_RANGE_HOURS = intPreferencesKey("time_range_hours")
        val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
    }
}
