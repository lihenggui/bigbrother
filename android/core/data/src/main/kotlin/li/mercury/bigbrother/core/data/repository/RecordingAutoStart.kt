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

import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.toLoadFailure
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether recording found stopped is started when the app is
 * opened, once per process. Without a root manager nothing starts an
 * installation this app made at boot. A module flashed in a root manager is
 * started by the manager: if it is stopped, there is a reason, and it is
 * left alone.
 */
@Singleton
class RecordingAutoStart @Inject constructor(private val moduleRepository: ModuleRepository) {
    private val attempted = AtomicBoolean(false)

    /** Whether to start recording now; true at most once per process. */
    suspend fun claim(): Boolean {
        if (attempted.getAndSet(true)) return false
        return try {
            moduleRepository.isInstalledByApp()
        } catch (e: RecordsException) {
            // Starting needs the same root access: it is left to the user.
            Timber.w("Installation origin unknown: %s", e.toLoadFailure())
            false
        }
    }
}
