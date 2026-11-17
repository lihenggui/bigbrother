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

package li.mercury.bigbrother

import androidx.profileinstaller.ProfileVerifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.common.di.ApplicationScope
import li.mercury.bigbrother.core.common.dispatchers.BigBrotherDispatchers.IO
import li.mercury.bigbrother.core.common.dispatchers.Dispatcher
import timber.log.Timber
import javax.inject.Inject

/**
 * Logs whether the app runs with its startup code compiled ahead of time
 * from the baseline profiles, as in Blocker: an app installed outside a
 * store has it compiled only later, when the device is idle.
 */
class ProfileVerifierLogger @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) {
    operator fun invoke() {
        scope.launch(ioDispatcher) {
            val status = ProfileVerifier.getCompilationStatusAsync().get()
            Timber.i(
                "Baseline profile: compiled %b, install result %d, awaiting compilation %b",
                status.isCompiledWithProfile,
                status.profileInstallResultCode,
                status.hasProfileEnqueuedForCompilation(),
            )
        }
    }
}
