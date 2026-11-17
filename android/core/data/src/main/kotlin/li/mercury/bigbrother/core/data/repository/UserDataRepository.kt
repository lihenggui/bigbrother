package li.mercury.bigbrother.core.data.repository

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import li.mercury.bigbrother.core.datastore.UserPreferencesDataSource
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData

/** The user's settings. */
interface UserDataRepository {
    val userData: Flow<UserData>

    suspend fun setTimeRange(range: TimeRange)

    suspend fun setShowSystemApps(show: Boolean)

    suspend fun setUseDynamicColor(use: Boolean)
}

internal class DataStoreUserDataRepository @Inject constructor(
    private val preferences: UserPreferencesDataSource,
) : UserDataRepository {
    override val userData: Flow<UserData> = preferences.userData

    override suspend fun setTimeRange(range: TimeRange) = preferences.setTimeRange(range)

    override suspend fun setShowSystemApps(show: Boolean) = preferences.setShowSystemApps(show)

    override suspend fun setUseDynamicColor(use: Boolean) = preferences.setUseDynamicColor(use)
}
