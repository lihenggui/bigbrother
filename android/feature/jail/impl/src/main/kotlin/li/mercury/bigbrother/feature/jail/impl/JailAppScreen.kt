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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.relativeTime
import li.mercury.bigbrother.core.ui.R as UiR

/** What the sandbox keeps from one app in the jail, item by item. */
@Composable
fun JailAppScreen(
    onBack: () -> Unit,
    onOpenRecords: (uid: Int, packageName: String) -> Unit,
    onLeave: () -> Unit,
    viewModel: JailAppViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    LaunchedEffect(state.left) { if (state.left) onLeave() }
    JailAppScreen(
        state = state,
        showBack = !LocalShowsDetailBesideList.current,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenRecords = onOpenRecords,
        onRelease = viewModel::release,
        onSetOn = viewModel::setOn,
        onChoose = viewModel::choose,
        onReset = viewModel::reset,
        onErrorDismiss = viewModel::dismissError,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun JailAppScreen(
    state: JailAppUiState,
    showBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRecords: (uid: Int, packageName: String) -> Unit,
    onRelease: () -> Unit,
    onSetOn: (SandboxItem, Boolean) -> Unit,
    onChoose: (SandboxItem, SandboxMode?) -> Unit,
    onReset: () -> Unit,
    onErrorDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: SandboxItem? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    ErrorEffect(state.error, snackbar, onErrorDismiss)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(nameWithUser(state.name, state.otherUser)) },
                subtitle = { Text(state.packageName) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                actions = {
                    if (state.app != null) IsolatedAppIcon(state.info, state.name, Modifier.padding(end = 16.dp))
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val app = state.app
        when {
            state.failure != null -> LoadFailureMessage(state.failure, onRetry = onRefresh, modifier = Modifier.padding(padding))

            state.loading -> BigBrotherLoadingIndicator(Modifier.fillMaxSize().padding(padding))

            // Only while the page leaves, for an app that was not in the
            // sandbox when it opened.
            app == null -> Unit

            else -> {
                var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
                var showAll by rememberSaveable { mutableStateOf(false) }
                // Not knowing what the app asks for, every item is listed as concerning it.
                val concerned = state.requested?.let { requested -> ITEM_ORDER.filter { it.concerns(requested) } } ?: ITEM_ORDER
                val others = ITEM_ORDER - concerned.toSet()
                val row: LazyListScope.(List<SandboxItem>) -> Unit = { items ->
                    items.forEachIndexed { index, item ->
                        item(key = item.id) {
                            ItemRow(
                                item = item,
                                mode = app.modeOf(item),
                                choice = app.settings[item],
                                globalMode = state.global[item] ?: SandboxMode.ON,
                                unmet = app.unmet[item].orEmpty(),
                                expanded = expanded == item,
                                onExpandedChange = { open -> expanded = if (open) item else null },
                                onCheckedChange = { on -> onSetOn(item, on) },
                                onChoose = { mode -> onChoose(item, mode) },
                                index = index,
                                count = items.size,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
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
                    item(key = "header") {
                        Actions(onRelease = onRelease, onOpenRecords = { onOpenRecords(app.uid, app.packageName) })
                    }
                    item(key = "items") {
                        Row {
                            SectionHeader(
                                stringResource(R.string.jail_section_items),
                                Modifier
                                    .weight(1f)
                                    .alignByBaseline(),
                            )
                            if (app.settings.isNotEmpty()) {
                                TextButton(onClick = onReset, modifier = Modifier.alignByBaseline()) {
                                    Text(stringResource(R.string.jail_reset))
                                }
                            }
                        }
                    }
                    item(key = "summary") { Note(stringResource(R.string.jail_items_summary)) }
                    row(concerned)
                    if (others.isNotEmpty()) {
                        item(key = "more") {
                            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (showAll) {
                                        stringResource(R.string.jail_fewer_items)
                                    } else {
                                        pluralStringResource(R.plurals.jail_more_items, others.size, others.size)
                                    },
                                )
                                Spacer(Modifier.size(6.dp))
                                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.rotate(if (showAll) 180f else 0f))
                            }
                        }
                        if (showAll) row(others)
                    }
                    item(key = "restart") { Note(stringResource(R.string.jail_restart_note), Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}

/** What can be done with the app: take it out of the sandbox, or see what it did. */
@Composable
private fun Actions(onRelease: () -> Unit, onOpenRecords: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = onRelease, modifier = Modifier.weight(1f)) {
            Icon(CageIcons.Open, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.jail_release))
        }
        FilledTonalButton(onClick = onOpenRecords, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.jail_records))
        }
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

/** Why a change was not made, in a line. */
@Composable
internal fun failureText(failure: LoadFailure): String = when (failure) {
    is LoadFailure.Other -> stringResource(R.string.jail_failed, failure.message)
    else -> failureReason(failure)
}

/** Why a change was not made, to follow a line that says which. */
@Composable
internal fun failureReason(failure: LoadFailure): String = when (failure) {
    LoadFailure.NoRoot -> stringResource(UiR.string.no_root_title)
    LoadFailure.ModuleMissing -> stringResource(UiR.string.no_module_title)
    LoadFailure.ModuleOutdated -> stringResource(UiR.string.old_module_title)
    is LoadFailure.Other -> failure.message
}

/** Tells once why a change was not made. */
@Composable
internal fun ErrorEffect(error: LoadFailure?, snackbar: SnackbarHostState, onDismiss: () -> Unit) {
    val text = error?.let { failureText(it) }
    LaunchedEffect(error) {
        if (text == null) return@LaunchedEffect
        snackbar.showSnackbar(text)
        onDismiss()
    }
}
