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
import li.mercury.bigbrother.core.model.LoadFailure
import timber.log.Timber
import javax.inject.Inject

/** Bringing the installed module up to date with the one this app carries. */
sealed interface ModuleUpdate {
    data object Idle : ModuleUpdate
    data object Updating : ModuleUpdate

    /** Disabled or marked for removal in the root manager, and left as it is. */
    data object Disabled : ModuleUpdate

    /** The files were replaced, but the new daemon did not report in. */
    data object NotReporting : ModuleUpdate

    /** Replacing the files failed, with the output saying why. */
    data class Failed(val reason: String?) : ModuleUpdate
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
            update.value = when {
                !moduleRepository.updateInPlace() -> ModuleUpdate.Disabled
                awaitRecording() -> ModuleUpdate.Idle
                else -> ModuleUpdate.NotReporting
            }
        } catch (e: RecordsException) {
            update.value = ModuleUpdate.Failed(installerError(e.message.orEmpty()))
        } finally {
            if (update.value == ModuleUpdate.Updating) update.value = ModuleUpdate.Idle
        }
        Timber.i("Module update: %s", update.value::class.simpleName)
    }

    /** The daemon reports only after it has written its first batch. */
    suspend fun awaitRecording(): Boolean {
        repeat(START_POLLS) {
            delay(START_POLL_INTERVAL_MS)
            val recording = try {
                accessRepository.getRecorderStatus().recording
            } catch (_: RecordsException) {
                false
            }
            if (recording) return true
        }
        return false
    }

    private companion object {
        const val START_POLLS = 20
        const val START_POLL_INTERVAL_MS = 1_000L
    }
}

/**
 * Why an installation failed, from the installer's output: the lines
 * Magisk and KernelSU mark with "!" for errors, else the last line. The
 * rest is the installer's banner and progress.
 */
fun installerError(output: String): String? {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val errors = lines.filter { it.startsWith("!") }.map { it.removePrefix("!").trim() }
    return errors.ifEmpty { listOfNotNull(lines.lastOrNull()) }.joinToString("\n").ifEmpty { null }
}
