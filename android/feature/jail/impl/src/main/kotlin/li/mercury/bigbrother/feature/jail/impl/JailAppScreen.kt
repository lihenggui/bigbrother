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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.ExpandChevron
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.Pop
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.appNameWithUser
import li.mercury.bigbrother.core.ui.failureReason
import li.mercury.bigbrother.feature.jail.impl.network.NetworkSectionActions
import li.mercury.bigbrother.feature.jail.impl.network.networkSection
import li.mercury.bigbrother.core.ui.R as UiR

/** What the sandbox keeps from one app in the jail, item by item. */
@Composable
fun JailAppScreen(
    onBack: () -> Unit,
    onOpenRecords: (uid: Int, packageName: String) -> Unit,
    onOpenNetwork: (uid: Int, packageName: String) -> Unit,
    onLeave: () -> Unit,
    viewModel: JailAppViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        val watch = viewModel.watchAppOps()
        onPauseOrDispose { watch.cancel() }
    }
    LeavingContent(state.left, onLeave)
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
        onAllowNetwork = viewModel::setNetworkAllowed,
        onSetRecording = viewModel::setRecording,
        onOpenNetwork = onOpenNetwork,
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
    onAllowNetwork: (Boolean) -> Unit,
    onSetRecording: (Boolean) -> Unit,
    onOpenNetwork: (uid: Int, packageName: String) -> Unit,
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
            JailAppTopBar(
                title = appNameWithUser(state.name, state.otherUser),
                subtitle = state.packageName,
                showBack = showBack,
                background = background,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                actions = {
                    Pop(state.app != null) { IsolatedAppIcon(state.info, state.name, Modifier.padding(end = 16.dp)) }
                },
            )
        },
    ) { padding ->
        FadeThrough(state, kind = { it.kind() }) { shown ->
            val app = shown.app
            when {
                shown.failure != null -> LoadFailureMessage(shown.failure, onRetry = onRefresh, modifier = Modifier.padding(padding))

                shown.loading -> BigBrotherLoadingIndicator(Modifier.fillMaxSize().padding(padding))

                // Only while the page leaves, for an app that was not in the
                // sandbox when it opened.
                app == null -> Unit

                else -> {
                    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
                    var showAll by rememberSaveable { mutableStateOf(false) }
                    JailAppItems(
                        state = shown,
                        app = app,
                        padding = padding,
                        expanded = expanded,
                        showAll = showAll,
                        onExpand = { expanded = it },
                        onToggleShowAll = { showAll = !showAll },
                        onRelease = onRelease,
                        onOpenRecords = onOpenRecords,
                        onOpenNetwork = onOpenNetwork,
                        onReset = onReset,
                        onSetOn = onSetOn,
                        onChoose = onChoose,
                        onAllowNetwork = onAllowNetwork,
                        onSetRecording = onSetRecording,
                    )
                }
            }
        }
    }
}

private fun JailAppUiState.kind(): ContentKind = when {
    failure != null -> ContentKind.Failure
    loading -> ContentKind.Loading
    app == null -> ContentKind.Empty
    else -> ContentKind.Content
}

@Composable
private fun LeavingContent(left: Boolean, onLeave: () -> Unit) {
    LaunchedEffect(left) { if (left) onLeave() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun JailAppTopBar(
    title: String,
    subtitle: String,
    showBack: Boolean,
    background: Color,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    MediumFlexibleTopAppBar(
        title = { Text(title) },
        subtitle = { Text(subtitle) },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.back))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun JailAppItems(
    state: JailAppUiState,
    app: SandboxedApp,
    padding: PaddingValues,
    expanded: SandboxItem?,
    showAll: Boolean,
    onExpand: (SandboxItem?) -> Unit,
    onToggleShowAll: () -> Unit,
    onRelease: () -> Unit,
    onOpenRecords: (uid: Int, packageName: String) -> Unit,
    onOpenNetwork: (uid: Int, packageName: String) -> Unit,
    onReset: () -> Unit,
    onSetOn: (SandboxItem, Boolean) -> Unit,
    onChoose: (SandboxItem, SandboxMode?) -> Unit,
    onAllowNetwork: (Boolean) -> Unit,
    onSetRecording: (Boolean) -> Unit,
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
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        item(key = "header") {
            Actions(onRelease = onRelease, onOpenRecords = { onOpenRecords(app.uid, app.packageName) }, modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme))
        }
        jailAppItemSection(state, app, expanded, showAll, onExpand, onToggleShowAll, onSetOn, onChoose, onReset)
        networkSection(
            app = app,
            globalMode = state.global[SandboxItem.NETWORK] ?: SandboxItem.NETWORK.default,
            requested = state.requested,
            destinations = state.destinations,
            destinationsFailure = state.destinationsFailure,
            expanded = expanded == SandboxItem.NETWORK,
            actions = NetworkSectionActions(
                onExpandedChange = { open -> onExpand(SandboxItem.NETWORK.takeIf { open }) },
                onSetAllowed = onAllowNetwork,
                onChoose = { mode -> onChoose(SandboxItem.NETWORK, mode) },
                onSetRecording = onSetRecording,
                onOpenActivity = { onOpenNetwork(app.uid, app.packageName) },
            ),
        )
        item(key = "restart") { Note(stringResource(R.string.jail_restart_note), Modifier.animatedRow(this, MaterialTheme.motionScheme).padding(top = 8.dp)) }
    }
}

private fun LazyListScope.jailAppItemSection(
    state: JailAppUiState,
    app: SandboxedApp,
    expanded: SandboxItem?,
    showAll: Boolean,
    onExpand: (SandboxItem?) -> Unit,
    onToggleShowAll: () -> Unit,
    onSetOn: (SandboxItem, Boolean) -> Unit,
    onChoose: (SandboxItem, SandboxMode?) -> Unit,
    onReset: () -> Unit,
) {
    // Not knowing what the app asks for, every item is listed as concerning it.
    val concerned = state.requested?.let { requested -> ITEM_ORDER.filter { it.concerns(requested) } } ?: ITEM_ORDER
    val others = ITEM_ORDER - concerned.toSet()
    item(key = "items") {
        Row(Modifier.animatedRow(this, MaterialTheme.motionScheme)) {
            SectionHeader(
                stringResource(R.string.jail_section_items),
                Modifier
                    .weight(1f)
                    .alignByBaseline(),
            )
            Pop(app.settings.keys.any { it in ITEM_ORDER }, Modifier.alignByBaseline()) {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.jail_reset))
                }
            }
        }
    }
    item(key = "summary") { Note(stringResource(R.string.jail_items_summary), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    jailItemRows(concerned, state, app, expanded, onExpand, onSetOn, onChoose)
    if (others.isNotEmpty()) {
        item(key = "more") {
            ShowAllToggle(showAll, others.size, onToggleShowAll, Modifier.animatedRow(this, MaterialTheme.motionScheme))
        }
        if (showAll) jailItemRows(others, state, app, expanded, onExpand, onSetOn, onChoose)
    }
}

private fun LazyListScope.jailItemRows(
    items: List<SandboxItem>,
    state: JailAppUiState,
    app: SandboxedApp,
    expanded: SandboxItem?,
    onExpand: (SandboxItem?) -> Unit,
    onSetOn: (SandboxItem, Boolean) -> Unit,
    onChoose: (SandboxItem, SandboxMode?) -> Unit,
) {
    items.forEachIndexed { index, item ->
        item(key = item.id) {
            ItemRow(
                item = item,
                mode = app.modeOf(item),
                choice = app.settings[item],
                globalMode = state.global[item] ?: SandboxMode.ON,
                unmet = app.unmet[item].orEmpty(),
                hookHealth = app.hookHealth,
                appOpsChecking = app.appOpsChecking,
                expanded = expanded == item,
                onExpandedChange = { open -> onExpand(item.takeIf { open }) },
                onCheckedChange = { on -> onSetOn(item, on) },
                onChoose = { mode -> onChoose(item, mode) },
                index = index,
                count = items.size,
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            )
        }
    }
}

@Composable
private fun ShowAllToggle(showAll: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        FadeThrough(showAll, kind = { it }) { shown ->
            Text(
                if (shown) {
                    stringResource(R.string.jail_fewer_items)
                } else {
                    pluralStringResource(R.plurals.jail_more_items, count, count)
                },
            )
        }
        Spacer(Modifier.size(6.dp))
        ExpandChevron(showAll)
    }
}

/** What can be done with the app: take it out of the sandbox, or see what it did. */
@Composable
private fun Actions(onRelease: () -> Unit, onOpenRecords: () -> Unit, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.padding(top = 8.dp)) {
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
    LoadFailure.NoRoot, LoadFailure.ModuleMissing, LoadFailure.ModuleOutdated -> failureReason(failure)
    else -> stringResource(R.string.jail_failed, failureReason(failure))
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
