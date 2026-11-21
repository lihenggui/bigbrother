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

package li.mercury.bigbrother.core.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Saves settings from a screen and keeps failed writes available for retry. */
class UserSettingsWriter(
    private val scope: CoroutineScope,
    private val warn: (String) -> Unit,
) {
    class Failure internal constructor(
        internal val setting: Setting,
        internal val write: suspend () -> Unit,
    )

    enum class Setting {
        TIME_RANGE,
        SHOW_SYSTEM_APPS,
        USE_DYNAMIC_COLOR,
    }

    private val mutex = Mutex()
    private val pendingFailures = MutableStateFlow<List<Failure>>(emptyList())
    val message = pendingFailures.map { it.firstOrNull() }.distinctUntilChanged()

    fun resolve(failure: Failure, retry: Boolean) {
        scope.launch {
            mutex.withLock {
                // A newer choice may have replaced the write while its message was shown.
                if (failure !in pendingFailures.value) return@withLock
                if (retry) persist(failure.setting, failure.write) else pendingFailures.value -= failure
            }
        }
    }

    fun save(setting: Setting, write: suspend () -> Unit) {
        scope.launch { mutex.withLock { persist(setting, write) } }
    }

    private suspend fun persist(setting: Setting, write: suspend () -> Unit) {
        pendingFailures.value = pendingFailures.value.filterNot { it.setting == setting }
        try {
            write()
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            warn("Could not save setting $setting")
            pendingFailures.value += Failure(setting, write)
        }
    }
}
