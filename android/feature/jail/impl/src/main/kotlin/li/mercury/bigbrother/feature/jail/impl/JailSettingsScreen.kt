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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.SegmentedSwitchRow
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.model.SandboxHookState
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.feature.jail.impl.network.globalNetworkSection

/** The global settings of the jail, item by item, and what works on this device. */
@Composable
fun JailSettingsScreen(
    onBack: () -> Unit,
    viewModel: JailSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    JailSettingsScreen(
        state = state,
        showBack = !LocalShowsDetailBesideList.current,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSetOn = viewModel::setOn,
        onChoose = viewModel::choose,
        onSetAutoIsolate = viewModel::setAutoIsolate,
        onReleaseAll = viewModel::releaseAll,
        onErrorDismiss = viewModel::dismissError,
        onAllowNetwork = viewModel::setNetworkAllowed,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun JailSettingsScreen(
    state: JailSettingsUiState,
    showBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSetOn: (SandboxItem, Boolean) -> Unit,
    onChoose: (SandboxItem, SandboxMode) -> Unit,
    onSetAutoIsolate: (Boolean) -> Unit,
    onReleaseAll: () -> Unit,
    onErrorDismiss: () -> Unit,
    onAllowNetwork: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    ErrorEffect(state.error, snackbar, onErrorDismiss)
    val left = state.notReleased
    val notReleased = pluralStringResource(R.plurals.jail_release_all_left, left, left)
    LaunchedEffect(left) {
        if (left == 0) return@LaunchedEffect
        snackbar.showSnackbar(notReleased)
        onErrorDismiss()
    }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            JailAppTopBar(
                title = stringResource(R.string.jail_global_settings),
                subtitle = stringResource(R.string.jail_settings_subtitle),
                showBack = showBack,
                background = background,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { padding ->
        FadeThrough(state, kind = { ContentKind.of(it.failure, it.global) }) { shown ->
            val global = shown.global
            when {
                shown.failure != null -> LoadFailureMessage(shown.failure, onRetry = onRefresh, modifier = Modifier.padding(padding))

                global == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize().padding(padding))

                else -> {
                    var expanded by rememberSaveable { mutableStateOf<SandboxItem?>(null) }
                    JailSettingsList(
                        state = shown,
                        global = global,
                        padding = padding,
                        expanded = expanded,
                        onExpand = { expanded = it },
                        onSetOn = onSetOn,
                        onChoose = onChoose,
                        onSetAutoIsolate = onSetAutoIsolate,
                        onAllowNetwork = onAllowNetwork,
                        onConfirmRelease = { confirming = true },
                    )
                }
            }
        }
    }
    if (confirming) {
        ReleaseAllDialog(
            isolated = state.isolated,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onReleaseAll()
            },
        )
    }
}

@Composable
private fun JailSettingsList(
    state: JailSettingsUiState,
    global: Map<SandboxItem, SandboxMode>,
    padding: PaddingValues,
    expanded: SandboxItem?,
    onExpand: (SandboxItem?) -> Unit,
    onSetOn: (SandboxItem, Boolean) -> Unit,
    onChoose: (SandboxItem, SandboxMode) -> Unit,
    onSetAutoIsolate: (Boolean) -> Unit,
    onAllowNetwork: (Boolean) -> Unit,
    onConfirmRelease: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 840.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding(),
            bottom = 24.dp + padding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        globalItemsSection(state, global, expanded, onExpand, onSetOn, onChoose, onSetAutoIsolate, onAllowNetwork)
        val holdNeeded = SandboxItem.entries.any { it != SandboxItem.NETWORK_RECORD && (global[it] ?: it.default).isOn }
        statusSection(state, holdNeeded, onConfirmRelease)
    }
}

private fun LazyListScope.globalItemsSection(
    state: JailSettingsUiState,
    global: Map<SandboxItem, SandboxMode>,
    expanded: SandboxItem?,
    onExpand: (SandboxItem?) -> Unit,
    onSetOn: (SandboxItem, Boolean) -> Unit,
    onChoose: (SandboxItem, SandboxMode) -> Unit,
    onSetAutoIsolate: (Boolean) -> Unit,
    onAllowNetwork: (Boolean) -> Unit,
) {
    item(key = "auto_isolate", contentType = "switch") {
        SegmentedSwitchRow(
            title = stringResource(R.string.jail_auto_isolate),
            summary = stringResource(R.string.jail_auto_isolate_summary),
            checked = state.autoIsolate,
            onCheckedChange = onSetAutoIsolate,
            leadingContent = { ShapedIcon(Icons.Outlined.InstallMobile) },
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme).padding(top = 8.dp, bottom = 16.dp),
            showThumbIcon = true,
        )
    }
    item(key = "summary", contentType = "note") { Note(stringResource(R.string.jail_items_summary), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    for (group in ItemGroup.entries) {
        item(key = group.name, contentType = "section") { SectionHeader(stringResource(group.title), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
        group.items.forEachIndexed { index, item ->
            item(key = item.id, contentType = "jail-item") {
                val mode = global[item] ?: SandboxMode.ON
                ItemRow(
                    item = item,
                    mode = mode,
                    choice = mode,
                    globalMode = null,
                    unmet = state.status?.unmet(item).orEmpty(),
                    hookHealth = state.status?.hookHealth.orEmpty(),
                    expanded = expanded == item,
                    onExpandedChange = { open -> onExpand(item.takeIf { open }) },
                    onCheckedChange = { on -> onSetOn(item, on) },
                    onChoose = { chosen -> if (chosen != null) onChoose(item, chosen) },
                    index = index,
                    count = group.items.size,
                    modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
                )
            }
        }
    }
    item(key = "restart", contentType = "note") { Note(stringResource(R.string.jail_restart_note), Modifier.animatedRow(this, MaterialTheme.motionScheme).padding(top = 8.dp)) }
    globalNetworkSection(
        mode = global[SandboxItem.NETWORK] ?: SandboxItem.NETWORK.default,
        unmet = state.status?.unmet(SandboxItem.NETWORK).orEmpty(),
        expanded = expanded == SandboxItem.NETWORK,
        onExpandedChange = { open -> onExpand(SandboxItem.NETWORK.takeIf { open }) },
        onSetAllowed = onAllowNetwork,
        onChoose = { mode -> onChoose(SandboxItem.NETWORK, mode) },
    )
}

private fun LazyListScope.statusSection(state: JailSettingsUiState, holdNeeded: Boolean, onConfirmRelease: () -> Unit) {
    item(key = "status_header", contentType = "section") { SectionHeader(stringResource(R.string.jail_section_status), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    item(key = "status", contentType = "status") { StatusRow(state.status, state.autoIsolate && holdNeeded, Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    item(key = "release_all", contentType = "release") {
        LinkRow(
            title = stringResource(R.string.jail_release_all),
            supporting = stringResource(R.string.jail_release_all_summary),
            onClick = onConfirmRelease,
            index = 1,
            count = 2,
            leading = { ShapedIcon(CageIcons.Open) },
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        )
    }
}

@Composable
private fun ReleaseAllDialog(isolated: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jail_release_all_title)) },
        text = { Text(pluralStringResource(R.plurals.jail_release_all_body, isolated, isolated)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.jail_release)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.jail_cancel)) }
        },
    )
}

/** Which parts of the sandbox work on this device; until known, neither good nor bad. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusRow(status: SandboxStatus?, holdExpected: Boolean, modifier: Modifier = Modifier) {
    val problems = buildList {
        if (status != null && !status.gate) add(stringResource(R.string.jail_status_gate_missing))
        if (status != null) {
            val unmet = status.unmetHooks()
            if (unmet.isNotEmpty()) {
                val text = when (hookFailure(unmet, status.hookHealth)) {
                    SandboxHookState.NOT_LOADED -> R.string.jail_status_hook_missing
                    SandboxHookState.INCOMPATIBLE -> R.string.jail_hook_incompatible
                    SandboxHookState.DEGRADED -> R.string.jail_hook_degraded
                    else -> R.string.jail_hook_unavailable
                }
                add(stringResource(text))
            }
        }
        if (status != null && !status.appOps) add(stringResource(R.string.jail_status_appops_missing))
        if (status != null && !status.network) add(stringResource(R.string.jail_status_network_missing))
        val pending = status?.newAppsPending ?: 0
        if (pending > 0) {
            val text = if (status?.newAppsHeld == true) R.plurals.jail_status_new_apps_held else R.plurals.jail_status_new_apps_pending
            add(pluralStringResource(text, pending, pending))
        }
        val failed = status?.newAppsFailed ?: 0
        if (failed > 0) {
            add(pluralStringResource(R.plurals.jail_status_new_apps_failed, failed, failed))
        }
        if (holdExpected && status != null && !status.newAppsHeld) {
            add(stringResource(R.string.jail_status_new_apps_not_held))
        }
    }
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(0, 2),
        colors = ListItemDefaults.segmentedColors(),
        // The status arrives after the screen opens, and can change while it
        // is open: icon and text fade through to it in place.
        leadingContent = {
            FadeThrough((status != null) to problems.isEmpty(), kind = { it }) { (known, fine) ->
                when {
                    !known -> ShapedIcon(Icons.Outlined.HourglassEmpty)
                    fine -> ShapedIcon(Icons.Outlined.CheckCircle)
                    else -> ShapedIcon(Icons.Outlined.ErrorOutline, containerColor = colors.errorContainer, contentColor = colors.onErrorContainer)
                }
            }
        },
        supportingContent = {
            val text = when {
                status == null -> stringResource(R.string.jail_status_checking)
                problems.isEmpty() -> stringResource(R.string.jail_status_ok)
                else -> problems.joinToString(stringResource(R.string.jail_list_separator))
            }
            val color = if (status != null && problems.isNotEmpty()) colors.error else colors.onSurfaceVariant
            FadeThrough(text to color, kind = { it }) { (shown, tint) -> Text(shown, color = tint) }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(stringResource(R.string.jail_status_title)) }
}
