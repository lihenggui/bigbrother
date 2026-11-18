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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxStatus
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.R as UiR

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
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.jail_global_settings)) },
                subtitle = { Text(stringResource(R.string.jail_settings_subtitle)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val global = state.global
        when {
            state.failure != null -> LoadFailureMessage(state.failure, onRetry = onRefresh, modifier = Modifier.padding(padding))

            global == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize().padding(padding))

            else -> {
                var expanded by rememberSaveable { mutableStateOf<SandboxItem?>(null) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = 840.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding(),
                        bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    item(key = "auto_isolate") {
                        AutoIsolateRow(state.autoIsolate, onSetAutoIsolate, Modifier.padding(top = 8.dp, bottom = 16.dp))
                    }
                    item(key = "summary") { Note(stringResource(R.string.jail_items_summary)) }
                    for (group in ItemGroup.entries) {
                        item(key = group.name) { SectionHeader(stringResource(group.title)) }
                        group.items.forEachIndexed { index, item ->
                            item(key = item.id) {
                                val mode = global[item] ?: SandboxMode.ON
                                ItemRow(
                                    item = item,
                                    mode = mode,
                                    choice = mode,
                                    globalMode = null,
                                    inEffect = state.status?.supports(item) ?: true,
                                    expanded = expanded == item,
                                    onExpandedChange = { open -> expanded = if (open) item else null },
                                    onCheckedChange = { on -> onSetOn(item, on) },
                                    onChoose = { chosen -> if (chosen != null) onChoose(item, chosen) },
                                    index = index,
                                    count = group.items.size,
                                )
                            }
                        }
                    }
                    item(key = "restart") { Note(stringResource(R.string.jail_restart_note), Modifier.padding(top = 8.dp)) }
                    item(key = "status_header") { SectionHeader(stringResource(R.string.jail_section_status)) }
                    item(key = "status") { StatusRow(state.status) }
                    item(key = "release_all") {
                        LinkRow(
                            title = stringResource(R.string.jail_release_all),
                            supporting = stringResource(R.string.jail_release_all_summary),
                            onClick = { confirming = true },
                            index = 1,
                            count = 2,
                            leading = { ShapedIcon(CageIcons.Open) },
                        )
                    }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.jail_release_all_title)) },
            text = { Text(pluralStringResource(R.plurals.jail_release_all_body, state.isolated, state.isolated)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onReleaseAll()
                }) { Text(stringResource(R.string.jail_release)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.jail_cancel)) }
            },
        )
    }
}

/**
 * Whether apps installed from now on are isolated with these settings. The
 * whole row toggles it, as a switch takes effect at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AutoIsolateRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier
            .clip(segmentShape(0, 1))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { ShapedIcon(Icons.Outlined.InstallMobile) },
        supportingContent = { Text(stringResource(R.string.jail_auto_isolate_summary)) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = {
                    Icon(
                        if (checked) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(stringResource(R.string.jail_auto_isolate)) }
}

/** Which parts of the sandbox work on this device; until known, neither good nor bad. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusRow(status: SandboxStatus?) {
    val problems = buildList {
        if (status != null && !status.gate) add(stringResource(R.string.jail_status_gate_missing))
        if (status != null && (!status.androidIdHook || !status.appListHook)) add(stringResource(R.string.jail_status_hook_missing))
        if (status != null && !status.appOps) add(stringResource(R.string.jail_status_appops_missing))
        val pending = status?.newAppsPending ?: 0
        if (pending > 0) {
            val text = if (status?.newAppsHeld == true) R.plurals.jail_status_new_apps_held else R.plurals.jail_status_new_apps_pending
            add(pluralStringResource(text, pending, pending))
        }
    }
    val colors = MaterialTheme.colorScheme
    ListItem(
        shapes = ListItemDefaults.segmentedShapes(0, 2),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = {
            when {
                status == null -> ShapedIcon(Icons.Outlined.HourglassEmpty)
                problems.isEmpty() -> ShapedIcon(Icons.Outlined.CheckCircle)
                else -> ShapedIcon(Icons.Outlined.ErrorOutline, containerColor = colors.errorContainer, contentColor = colors.onErrorContainer)
            }
        },
        supportingContent = {
            when {
                status == null -> Text(stringResource(R.string.jail_status_checking), color = colors.onSurfaceVariant)
                problems.isEmpty() -> Text(stringResource(R.string.jail_status_ok), color = colors.onSurfaceVariant)
                else -> Text(problems.joinToString(stringResource(R.string.jail_list_separator)), color = colors.error)
            }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(stringResource(R.string.jail_status_title)) }
}
