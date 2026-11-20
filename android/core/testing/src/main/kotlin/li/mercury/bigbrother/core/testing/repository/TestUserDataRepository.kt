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

package li.mercury.bigbrother.core.testing.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import li.mercury.bigbrother.core.data.repository.UserDataRepository
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.UserData

/** Settings kept in memory. */
class TestUserDataRepository(initial: UserData = UserData()) : UserDataRepository {
    private val state = MutableStateFlow(initial)
    var writeFailure: Exception? = null

    override val userData: Flow<UserData> = state

    override suspend fun setTimeRange(range: TimeRange) = edit { it.copy(timeRange = range) }

    override suspend fun setShowSystemApps(show: Boolean) = edit { it.copy(showSystemApps = show) }

    override suspend fun setUseDynamicColor(use: Boolean) = edit { it.copy(useDynamicColor = use) }

    private fun edit(transform: (UserData) -> UserData) {
        writeFailure?.let { throw it }
        state.update(transform)
    }
}
