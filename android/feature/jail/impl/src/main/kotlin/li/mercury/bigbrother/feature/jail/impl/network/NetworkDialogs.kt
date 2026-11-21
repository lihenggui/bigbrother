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

package li.mercury.bigbrother.feature.jail.impl.network

import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.ui.appNameWithUser
import li.mercury.bigbrother.feature.jail.impl.R

/** A dialog the page can show. */
internal enum class NetworkDialog { BLOCK, CLEAR }

@Composable
internal fun NetworkDialogs(
    dialog: NetworkDialog?,
    state: NetworkActivityUiState,
    requestFocus: Boolean,
    onBlock: (NetworkTarget, Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        NetworkDialog.BLOCK -> BlockTargetDialog(
            onBlock = { target ->
                onDismiss()
                onBlock(target, true)
            },
            onDismiss = onDismiss,
            requestFocus = requestFocus,
        )

        NetworkDialog.CLEAR -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.jail_net_clear_title)) },
            text = { Text(stringResource(R.string.jail_net_clear_body, appNameWithUser(state.name, state.otherUser))) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    onClear()
                }) { Text(stringResource(R.string.jail_net_clear)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.jail_cancel)) } },
        )

        null -> Unit
    }
}
