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

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.feature.jail.impl.R

/**
 * Blocks a domain or an address typed in, before anything tried to reach
 * it. What is not one is said under the field once the user tries to block
 * it, not while it is being typed.
 */
@Composable
internal fun BlockTargetDialog(
    onBlock: (NetworkTarget) -> Unit,
    onDismiss: () -> Unit,
    initialText: String = "",
    requestFocus: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        val target = NetworkTarget.parse(text)
        if (target == null) invalid = true else onBlock(target)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jail_net_add_title)) },
        text = {
            OutlinedTextField(
                value = text,
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left),
                onValueChange = {
                    text = it
                    invalid = false
                },
                modifier = Modifier.focusRequester(focus),
                label = { Text(stringResource(R.string.jail_net_add_label)) },
                supportingText = {
                    Text(stringResource(if (invalid) R.string.jail_net_add_invalid else R.string.jail_net_add_supporting))
                },
                isError = invalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = text.isNotBlank()) { Text(stringResource(R.string.jail_net_block)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.jail_cancel)) }
        },
    )
}
