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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import li.mercury.bigbrother.core.model.TimeRange
import timber.log.Timber
import java.io.IOException

/** Saves settings from a screen and keeps failed writes available for retry. */
class UserSettingsWriter(
    private val scope: CoroutineScope,
    private val repository: UserDataRepository,
) {
    class Failure internal constructor(
        internal val setting: Setting,
        internal val write: suspend () -> Unit,
    )

    internal enum class Setting {
        TIME_RANGE,
        SHOW_SYSTEM_APPS,
        USE_DYNAMIC_COLOR,
    }

    private val mutex = Mutex()
    private val _failures = MutableStateFlow<List<Failure>>(emptyList())
    val failures = _failures.asStateFlow()

    fun setTimeRange(range: TimeRange) {
        save(Setting.TIME_RANGE) { repository.setTimeRange(range) }
    }

    fun setShowSystemApps(show: Boolean) {
        save(Setting.SHOW_SYSTEM_APPS) { repository.setShowSystemApps(show) }
    }

    fun setUseDynamicColor(use: Boolean) {
        save(Setting.USE_DYNAMIC_COLOR) { repository.setUseDynamicColor(use) }
    }

    fun resolve(failure: Failure, retry: Boolean) {
        scope.launch {
            mutex.withLock {
                // A newer choice may have replaced the write while its message was shown.
                if (failure !in _failures.value) return@withLock
                if (retry) persist(failure.setting, failure.write) else _failures.value -= failure
            }
        }
    }

    private fun save(setting: Setting, write: suspend () -> Unit) {
        scope.launch { mutex.withLock { persist(setting, write) } }
    }

    private suspend fun persist(setting: Setting, write: suspend () -> Unit) {
        _failures.value = _failures.value.filterNot { it.setting == setting }
        try {
            write()
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            Timber.w("Could not save setting %s", setting)
            _failures.value += Failure(setting, write)
        }
    }
}
