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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.ui.PredictiveSearchTopBar
import li.mercury.bigbrother.core.ui.appNameWithUser
import li.mercury.bigbrother.feature.jail.impl.R
import li.mercury.bigbrother.core.ui.R as UiR

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NetworkTopBar(
    state: NetworkActivityUiState,
    searching: Boolean,
    background: Color,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: NetworkActivityActions,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onDialog: (NetworkDialog) -> Unit,
) {
    val recording = state.activity?.recording
    PredictiveSearchTopBar(
        searching = searching,
        query = state.query,
        onQueryChange = actions.onQuery,
        placeholder = stringResource(R.string.jail_net_search),
        onClose = onCloseSearch,
    ) {
        MediumFlexibleTopAppBar(
            title = { Text(stringResource(R.string.jail_network_activity)) },
            subtitle = {
                val name = appNameWithUser(state.name, state.otherUser)
                Text(
                    when (recording?.on) {
                        null -> name
                        true -> stringResource(R.string.jail_net_subtitle_recording, name)
                        false -> stringResource(R.string.jail_net_subtitle_stopped, name)
                    },
                )
            },
            navigationIcon = {
                IconButton(onClick = actions.onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.back))
                }
            },
            actions = {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(UiR.string.search))
                }
                OverflowMenu(recording?.on, onDialog = onDialog, onSetRecording = actions.onSetRecording)
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
private fun OverflowMenu(recording: Boolean?, onDialog: (NetworkDialog) -> Unit, onSetRecording: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.jail_net_more_options))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.jail_net_menu_block)) },
            onClick = {
                open = false
                onDialog(NetworkDialog.BLOCK)
            },
        )
        if (recording != null) {
            DropdownMenuItem(
                text = { Text(stringResource(if (recording) R.string.jail_net_menu_stop else R.string.jail_net_start)) },
                onClick = {
                    open = false
                    onSetRecording(!recording)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.jail_net_menu_clear)) },
            onClick = {
                open = false
                onDialog(NetworkDialog.CLEAR)
            },
        )
    }
}
