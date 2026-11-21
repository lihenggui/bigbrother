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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.LoadFailure
import timber.log.Timber
import javax.inject.Inject

/** Bringing the installed module up to date with the one this app carries. */
sealed interface ModuleUpdate {
    data object Idle : ModuleUpdate
    data object Updating : ModuleUpdate

    data class Failed(val failure: LoadFailure) : ModuleUpdate
}

/** Checks the installed module against this app and tracks an in-place update. */
class ModuleUpdater @Inject constructor(
    private val moduleRepository: ModuleRepository,
    private val accessRepository: AccessRepository,
) {
    private val update = MutableStateFlow<ModuleUpdate>(ModuleUpdate.Idle)
    val state = update.asStateFlow()

    private var moduleCurrent = false

    // A failed automatic update is shown with a way to try again.
    private var automaticAttempted = false

    /** Check the installed module after reading records. An outdated command can also trigger an update. */
    suspend fun shouldUpdate(failure: LoadFailure?): Boolean {
        if (automaticAttempted) return false
        val needed = when {
            failure == LoadFailure.ModuleOutdated -> true

            failure != null || moduleCurrent -> false

            else -> {
                val installed = moduleRepository.compareWithBundled()
                Timber.i("Installed module: %s", installed)
                moduleCurrent = installed == InstalledModule.CURRENT
                installed == InstalledModule.OUTDATED
            }
        }
        if (needed) automaticAttempted = true
        return needed
    }

    suspend fun updateInPlace() {
        update.value = ModuleUpdate.Updating
        try {
            moduleRepository.updateInPlace()
            awaitRecording()
            update.value = ModuleUpdate.Idle
        } catch (e: RecordsException) {
            update.value = ModuleUpdate.Failed(e.toLoadFailure())
        } finally {
            if (update.value == ModuleUpdate.Updating) update.value = ModuleUpdate.Idle
        }
        Timber.i("Module update: %s", update.value::class.simpleName)
    }

    /** The daemon reports only after it has written its first batch. */
    suspend fun awaitRecording() {
        repeat(START_POLLS) {
            delay(START_POLL_INTERVAL_MS)
            val recording = try {
                accessRepository.getRecorderStatus().recording
            } catch (_: RecordsException) {
                // The command can fail while the daemon is between generations.
                false
            }
            if (recording) return
        }
        throw RecordsException.CommandFailed(LoadFailure.Operation.DAEMON_NOT_REPORTING)
    }

    private companion object {
        const val START_POLLS = 20
        const val START_POLL_INTERVAL_MS = 1_000L
    }
}
