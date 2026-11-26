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

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.isolateLtr
import li.mercury.bigbrother.feature.jail.impl.ErrorEffect
import li.mercury.bigbrother.feature.jail.impl.JailSnackbarHost
import li.mercury.bigbrother.feature.jail.impl.R

/** What can be done on the network activity page. */
internal class NetworkActivityActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRange: (TimeRange) -> Unit = {},
    val onSelectBucket: (Long?) -> Unit = {},
    val onBlockedOnly: (Boolean) -> Unit = {},
    val onSort: (DestinationSort) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onShowAllDomains: () -> Unit = {},
    val onShowAllAddresses: () -> Unit = {},
    val onOpen: (NetworkTarget) -> Unit = {},
    val onBlock: (NetworkTarget, Boolean) -> Unit = { _, _ -> },
    val onUnblock: (NetworkTarget) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onSetRecording: (Boolean) -> Unit = {},
    val onClear: () -> Unit = {},
    val onMessageDismiss: () -> Unit = {},
    val onErrorDismiss: () -> Unit = {},
)

/**
 * Where an app in the jail connected to: how much, when, to which domains
 * and addresses, with a button on each to block it. [selectedTarget] is the
 * destination open beside the page, as the daemon writes it.
 */
@Composable
fun NetworkActivityScreen(
    selectedTarget: String?,
    onBack: () -> Unit,
    onOpenDestination: (target: String, rangeHours: Int) -> Unit,
    viewModel: NetworkActivityViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    NetworkActivityScreen(
        state = state,
        selectedTarget = selectedTarget,
        actions = NetworkActivityActions(
            onBack = onBack,
            onRefresh = viewModel::refresh,
            onRange = viewModel::setRange,
            onSelectBucket = viewModel::selectBucket,
            onBlockedOnly = viewModel::setBlockedOnly,
            onSort = viewModel::setSort,
            onQuery = viewModel::setQuery,
            onShowAllDomains = viewModel::showAllDomains,
            onShowAllAddresses = viewModel::showAllAddresses,
            onOpen = { onOpenDestination(it.id, state.range.hours) },
            onBlock = viewModel::block,
            onUnblock = viewModel::unblock,
            onUndo = viewModel::undo,
            onSetRecording = viewModel::setRecording,
            onClear = viewModel::clearRecords,
            onMessageDismiss = viewModel::dismissMessage,
            onErrorDismiss = viewModel::dismissError,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NetworkActivityScreen(
    state: NetworkActivityUiState,
    selectedTarget: String?,
    actions: NetworkActivityActions,
    modifier: Modifier = Modifier,
    initiallySearching: Boolean = false,
    initialDialog: NetworkDialog? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    ErrorEffect(state.error, snackbar, actions.onErrorDismiss)
    MessageEffect(state.message, snackbar, actions.onUndo, actions.onMessageDismiss)
    var searching by rememberSaveable { mutableStateOf(initiallySearching) }
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        snackbarHost = { JailSnackbarHost(snackbar) },
        topBar = {
            NetworkTopBar(
                state = state,
                searching = searching,
                background = background,
                scrollBehavior = scrollBehavior,
                actions = actions,
                onOpenSearch = { searching = true },
                onCloseSearch = {
                    searching = false
                    actions.onQuery("")
                },
                onDialog = { dialog = it },
            )
        },
    ) { padding ->
        NetworkActivityList(state, selectedTarget, padding, actions, onDialog = { dialog = it })
    }
    NetworkDialogs(
        dialog = dialog,
        state = state,
        requestFocus = initialDialog == null,
        onBlock = actions.onBlock,
        onClear = actions.onClear,
        onDismiss = { dialog = null },
    )
}

/** Tells of a block or unblock, with Undo, until it is undone or replaced. */
@Composable
internal fun MessageEffect(message: NetworkMessage?, snackbar: SnackbarHostState, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val text = message?.let {
        stringResource(if (it is NetworkMessage.Blocked) R.string.jail_net_blocked_message else R.string.jail_net_unblocked_message, isolateLtr(it.target.name))
    }
    val undo = stringResource(R.string.jail_undo)
    LaunchedEffect(message) {
        if (text == null) return@LaunchedEffect
        when (snackbar.showSnackbar(text, actionLabel = undo)) {
            SnackbarResult.ActionPerformed -> onUndo()
            SnackbarResult.Dismissed -> onDismiss()
        }
    }
}
