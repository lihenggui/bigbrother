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

package li.mercury.bigbrother.feature.jail.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.R as UiR

/**
 * The jail: the apps the user put in the sandbox, the global settings, and
 * adding apps. On wide screens it is the list beside an app's settings.
 */
@Composable
fun JailScreen(
    topBar: @Composable () -> Unit,
    selectedUid: Int?,
    settingsSelected: Boolean,
    onOpenApp: (IsolatedApp) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: JailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    JailScreen(
        state = state,
        message = message,
        topBar = topBar,
        selectedUid = selectedUid,
        settingsSelected = settingsSelected,
        onRefresh = viewModel::refresh,
        onOpenApp = onOpenApp,
        onOpenSettings = onOpenSettings,
        onAdd = { adding = true },
        onUndo = { shown ->
            when (shown) {
                is JailMessage.Isolated -> viewModel.undoIsolate(shown.apps)
                is JailMessage.Failed -> Unit
            }
        },
        onMessageDismiss = viewModel::dismissMessage,
        modifier = modifier,
    )
    if (adding) {
        AddAppsDialog(
            onDismiss = { adding = false },
            onIsolate = { apps ->
                adding = false
                viewModel.isolate(apps)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JailScreen(
    state: JailUiState,
    message: JailMessage?,
    topBar: @Composable () -> Unit,
    selectedUid: Int?,
    settingsSelected: Boolean,
    onRefresh: () -> Unit,
    onOpenApp: (IsolatedApp) -> Unit,
    onOpenSettings: () -> Unit,
    onAdd: () -> Unit,
    onUndo: (JailMessage) -> Unit,
    onMessageDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(message, snackbar, onUndo, onMessageDismiss)
    val listState = rememberLazyListState()
    // The button says what it does while the list is at its top, and gets
    // out of the way as it scrolls.
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = topBar,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!state.apps.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    expanded = atTop,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.jail_add_apps)) },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing && state.apps != null,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            val apps = state.apps
            when {
                state.failure != null -> LoadFailureMessage(state.failure, onRetry = onRefresh)
                apps == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())
                else -> JailList(state, apps, listState, selectedUid, settingsSelected, onOpenApp, onOpenSettings, onAdd)
            }
        }
    }
}

@Composable
private fun JailList(
    state: JailUiState,
    apps: List<IsolatedApp>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    selectedUid: Int?,
    settingsSelected: Boolean,
    onOpenApp: (IsolatedApp) -> Unit,
    onOpenSettings: () -> Unit,
    onAdd: () -> Unit,
) {
    val beside = LocalShowsDetailBesideList.current
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            // Room for the button over the end of the list.
            bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        statusCards(state.status)
        if (apps.isEmpty()) {
            item(key = "empty") { EmptyJail(onAdd) }
        } else {
            item(key = "isolated") {
                SectionHeader(stringResource(R.string.jail_section_isolated), trailing = apps.size.toString())
            }
            itemsIndexed(apps, key = { _, app -> app.app.uid }) { index, app ->
                LinkRow(
                    title = app.name,
                    supporting = itemsSummary(app.app.items),
                    onClick = { onOpenApp(app) },
                    index = index,
                    count = apps.size,
                    leading = { IsolatedAppIcon(app.info, app.name) },
                    selected = beside && app.app.uid == selectedUid,
                    modifier = Modifier.animateItem(),
                )
            }
        }
        item(key = "settings") { SectionHeader(stringResource(R.string.jail_section_settings)) }
        item(key = "global") {
            LinkRow(
                title = stringResource(R.string.jail_global_settings),
                supporting = itemsSummary(SandboxItem.entries.associateWith { state.global[it] ?: SandboxMode.ON }),
                onClick = onOpenSettings,
                index = 0,
                count = 1,
                leading = { ShapedIcon(Icons.Outlined.Tune) },
                selected = beside && settingsSelected,
            )
        }
    }
}

/** What is not in effect on this device, said before anything else. */
private fun androidx.compose.foundation.lazy.LazyListScope.statusCards(status: SandboxStatus?) {
    if (status == null) return
    if (!status.androidIdHook || !status.appListHook) {
        item(key = "hook") {
            StatusCard(
                title = stringResource(R.string.jail_hook_missing_title),
                body = stringResource(R.string.jail_hook_missing),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
    if (!status.gate) {
        item(key = "gate") {
            StatusCard(
                title = stringResource(R.string.jail_gate_missing_title),
                body = stringResource(R.string.jail_gate_missing),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/** No app in the jail: what it does, and how to put one in. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyJail(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        ShapedIcon(
            CageIcons.Filled,
            size = 96.dp,
            iconSize = 44.dp,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.jail_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        val facts = listOf(
            Triple(Icons.Outlined.Folder, R.string.jail_empty_files_title, R.string.jail_empty_files),
            Triple(Icons.Outlined.Contacts, R.string.jail_empty_permissions_title, R.string.jail_empty_permissions),
            Triple(Icons.Outlined.Fingerprint, R.string.jail_empty_device_title, R.string.jail_empty_device),
        )
        facts.forEachIndexed { index, (icon, title, body) ->
            ListItem(
                shapes = ListItemDefaults.segmentedShapes(index, facts.size),
                colors = ListItemDefaults.segmentedColors(),
                leadingContent = { ShapedIcon(icon) },
                supportingContent = { Text(stringResource(body)) },
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(stringResource(title)) }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.height(56.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.jail_add_apps), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Shows [message] once, with a way to undo what it tells of. */
@Composable
private fun MessageEffect(
    message: JailMessage?,
    snackbar: SnackbarHostState,
    onUndo: (JailMessage) -> Unit,
    onDismiss: () -> Unit,
) {
    val separator = stringResource(R.string.jail_list_separator)
    val text = when (message) {
        is JailMessage.Isolated -> stringResource(R.string.jail_isolated, message.names.joinToString(separator))
        is JailMessage.Failed -> failureText(message.failure)
        null -> null
    }
    val undo = stringResource(R.string.jail_undo)
    LaunchedEffect(message) {
        if (message == null || text == null) return@LaunchedEffect
        val canUndo = message !is JailMessage.Failed
        val result = snackbar.showSnackbar(
            message = text,
            actionLabel = if (canUndo) undo else null,
            duration = if (canUndo) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        // Only now: taken away earlier, the message would end its own showing.
        onDismiss()
        if (result == SnackbarResult.ActionPerformed) onUndo(message)
    }
}

/** Opens the list of apps to add, and puts those chosen in the sandbox. */
@Composable
internal fun AddAppsDialog(
    onDismiss: () -> Unit,
    onIsolate: (List<InstalledApp>) -> Unit,
    viewModel: AddAppsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AddAppsDialog(
        state = state,
        onQueryChange = viewModel::setQuery,
        onToggle = viewModel::toggle,
        onDismiss = {
            viewModel.clear()
            onDismiss()
        },
        onConfirm = {
            val chosen = viewModel.chosen()
            viewModel.clear()
            onIsolate(chosen)
        },
    )
}
