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

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource

/** Offers a retry for the current write failure without replacing the screen's content. */
@Composable
fun <T : Any> rememberSettingsWriteSnackbar(failure: T?, onResult: (T, Boolean) -> Unit): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    val resolve by rememberUpdatedState(onResult)
    val message = stringResource(R.string.settings_write_failed)
    val action = stringResource(R.string.retry)
    LaunchedEffect(failure, message, action) {
        if (failure == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = action, withDismissAction = true)
        resolve(failure, result == SnackbarResult.ActionPerformed)
    }
    return snackbar
}
