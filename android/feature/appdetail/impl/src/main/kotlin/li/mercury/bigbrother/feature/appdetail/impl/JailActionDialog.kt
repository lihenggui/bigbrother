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

package li.mercury.bigbrother.feature.appdetail.impl

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.ui.failureReason

@Composable
internal fun JailActionDialog(action: JailAction?, appName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (action) {
        JailAction.Confirm, JailAction.Isolating -> {
            val busy = action == JailAction.Isolating
            AlertDialog(
                onDismissRequest = { if (!busy) onDismiss() },
                title = { Text(stringResource(R.string.isolate_app_title, appName)) },
                text = { Text(stringResource(if (busy) R.string.isolating_app else R.string.isolate_app_summary)) },
                confirmButton = {
                    TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.isolate_app)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }

        is JailAction.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.jail_action_failed)) },
            text = { Text(failureReason(action.failure)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        )

        else -> Unit
    }
}
