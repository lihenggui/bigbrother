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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.MenuFilterChip
import li.mercury.bigbrother.core.ui.MenuOption
import li.mercury.bigbrother.core.ui.SearchTopBar
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.TimeRangeButtonGroup
import li.mercury.bigbrother.feature.jail.impl.ErrorEffect
import li.mercury.bigbrother.feature.jail.impl.Note
import li.mercury.bigbrother.feature.jail.impl.R
import li.mercury.bigbrother.feature.jail.impl.StatusCard
import li.mercury.bigbrother.feature.jail.impl.nameWithUser
import java.text.NumberFormat
import li.mercury.bigbrother.core.ui.R as UiR

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

/** A dialog the page can show. */
internal enum class NetworkDialog { BLOCK, CLEAR }

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
    MessageEffect(state.message, snackbar, actions)
    var searching by rememberSaveable { mutableStateOf(initiallySearching) }
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    val recording = state.activity?.recording
    val beside = LocalShowsDetailBesideList.current
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (searching) {
                SearchTopBar(
                    query = state.query,
                    onQueryChange = actions.onQuery,
                    placeholder = stringResource(R.string.jail_net_search),
                    onClose = {
                        searching = false
                        actions.onQuery("")
                    },
                )
            } else {
                MediumFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.jail_network_activity)) },
                    subtitle = {
                        val name = nameWithUser(state.name, state.otherUser)
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
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(UiR.string.search))
                        }
                        OverflowMenu(recording?.on, onDialog = { dialog = it }, onSetRecording = actions.onSetRecording)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        if (state.failure != null) {
            LoadFailureMessage(state.failure, onRetry = actions.onRefresh, modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = 840.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                activityContent(state, selectedTarget, beside, actions, onDialog = { dialog = it })
            }
        }
    }
    when (dialog) {
        NetworkDialog.BLOCK -> BlockTargetDialog(
            onBlock = { target ->
                dialog = null
                actions.onBlock(target, true)
            },
            onDismiss = { dialog = null },
            requestFocus = initialDialog == null,
        )

        NetworkDialog.CLEAR -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.jail_net_clear_title)) },
            text = { Text(stringResource(R.string.jail_net_clear_body, nameWithUser(state.name, state.otherUser))) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    actions.onClear()
                }) { Text(stringResource(R.string.jail_net_clear)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.jail_cancel)) } },
        )

        null -> Unit
    }
}

private fun LazyListScope.activityContent(
    state: NetworkActivityUiState,
    selectedTarget: String?,
    beside: Boolean,
    actions: NetworkActivityActions,
    onDialog: (NetworkDialog) -> Unit,
) {
    val activity = state.activity
    if (activity != null) notices(activity, actions)
    item(key = "range") {
        TimeRangeButtonGroup(
            range = state.range,
            onRange = actions.onRange,
            options = ACTIVITY_RANGES,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    if (activity == null) {
        item(key = "loading") { BigBrotherLoadingIndicator(Modifier.fillMaxWidth().padding(vertical = 96.dp)) }
        return
    }
    val nothing = activity.domains.isEmpty() && activity.addresses.isEmpty() && activity.rules.isEmpty() &&
        activity.buckets.all { it.connections + it.refused == 0 }
    if (nothing && activity.recording.on) {
        item(key = "empty") { Empty(activity.recording.since ?: activity.since) }
        return
    }
    item(key = "summary") { Summary(state, activity, actions.onSelectBucket) }
    item(key = "filters") { Filters(state, actions) }
    val shown = state.shown
    if (shown.domainCount > 0) {
        item(key = "domains") { SectionHeader(stringResource(R.string.jail_net_section_domains), trailing = shown.domainCount.toString()) }
        rows(shown.domains, selectedTarget, beside, actions)
        if (shown.domainCount > shown.domains.size) {
            item(key = "domains_all") { ShowAll(shown.domainCount, actions.onShowAllDomains) }
        }
    }
    if (shown.addressCount > 0) {
        item(key = "addresses") { SectionHeader(stringResource(R.string.jail_net_section_addresses), trailing = shown.addressCount.toString()) }
        if (!state.blockedOnly) item(key = "addresses_note") { Note(stringResource(R.string.jail_net_addresses_note)) }
        rows(shown.addresses, selectedTarget, beside, actions)
        if (shown.addressCount > shown.addresses.size) {
            item(key = "addresses_all") { ShowAll(shown.addressCount, actions.onShowAllAddresses) }
        }
    }
    if (shown.domainCount + shown.addressCount == 0 && (state.query.isNotBlank() || state.blockedOnly || state.bucket != null)) {
        item(key = "no_results") {
            Text(
                stringResource(R.string.jail_net_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
        }
    }
    if (state.blockedOnly) {
        item(key = "add") {
            TextButton(onClick = { onDialog(NetworkDialog.BLOCK) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.jail_net_menu_block))
            }
        }
    } else if (state.bucket == null) {
        item(key = "footer") { Note(stringResource(R.string.jail_net_footer), Modifier.padding(top = 8.dp)) }
    }
}

private fun LazyListScope.rows(
    rows: List<DestinationRow>,
    selectedTarget: String?,
    beside: Boolean,
    actions: NetworkActivityActions,
) {
    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
        val opens = row.opens
        DestinationRow(
            row = row,
            onOpen = opens?.let { { actions.onOpen(it) } },
            onToggle = { block ->
                val target = row.toggle ?: return@DestinationRow
                if (block) actions.onBlock(target, false) else actions.onUnblock(target)
            },
            index = index,
            count = rows.size,
            selected = if (beside) opens != null && opens.id == selectedTarget else null,
            modifier = Modifier.animateItem(),
        )
    }
}

/** What is not as usual for the app or the device, before anything else. */
private fun LazyListScope.notices(activity: NetworkActivity, actions: NetworkActivityActions) {
    if (!activity.recording.on) {
        item(key = "stopped") {
            val last = (activity.domains + activity.addresses).maxOfOrNull { it.last } ?: activity.until
            NoticeCard(
                icon = Icons.Outlined.History,
                title = null,
                body = stringResource(R.string.jail_net_stopped, dateTime(last)),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                action = stringResource(R.string.jail_net_start) to { actions.onSetRecording(true) },
            )
        }
    }
    if (!activity.networkAllowed) {
        item(key = "no_network") {
            NoticeCard(
                icon = Icons.Outlined.Block,
                title = stringResource(R.string.jail_net_no_network_title),
                body = stringResource(R.string.jail_net_no_network_body),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
    if (!activity.names) {
        item(key = "names") {
            StatusCard(
                title = stringResource(R.string.jail_net_names_missing_title),
                body = stringResource(R.string.jail_net_names_missing_body),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/** A note about the page as a whole, with an action if it calls for one. */
@Composable
private fun NoticeCard(
    icon: ImageVector,
    title: String?,
    body: String,
    containerColor: Color,
    contentColor: Color,
    action: Pair<String, () -> Unit>? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (action != null) 4.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    TextButton(onClick = action.second, modifier = Modifier.align(Alignment.End)) { Text(action.first) }
                }
            }
        }
    }
}

/** Recording, with nothing recorded yet: since when, and no empty chart. */
@Composable
private fun Empty(since: Long) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShapedIcon(Icons.Outlined.Public, size = 72.dp, iconSize = 32.dp)
        Text(stringResource(R.string.jail_net_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.jail_net_empty_body, dateTime(since)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * How many destinations, connections and how much data, then the chart.
 * Choosing another range cross-fades it rather than growing the bars.
 */
@Composable
private fun Summary(state: NetworkActivityUiState, activity: NetworkActivity, onSelect: (Long?) -> Unit) {
    val totals = state.totals
    val numbers = NumberFormat.getIntegerInstance()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
        val enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())
        val exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
        AnimatedContent(
            targetState = activity,
            contentKey = { it.bucketSeconds to it.until - it.since },
            transitionSpec = { enter togetherWith exit },
            label = "summary",
        ) { shown ->
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(numbers.format(totals.destinations), style = MaterialTheme.typography.displayMedium, modifier = Modifier.alignByBaseline())
                    Text(
                        pluralStringResource(R.plurals.jail_net_destinations, totals.destinations),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Text(
                    if (shown.traffic && shown.networkAllowed) {
                        pluralStringResource(
                            R.plurals.jail_net_facts,
                            totals.connections,
                            numbers.format(totals.connections),
                            formatBytes(totals.received),
                            formatBytes(totals.sent),
                        )
                    } else {
                        pluralStringResource(R.plurals.jail_net_facts_counts, totals.connections, numbers.format(totals.connections), numbers.format(totals.refused))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActivityChart(
                    buckets = shown.buckets,
                    bucketSeconds = shown.bucketSeconds,
                    zone = shown.zone,
                    selected = state.bucket,
                    onSelect = onSelect,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ChartLegend(Modifier.padding(top = 8.dp))
                if (!shown.traffic) {
                    Text(
                        stringResource(R.string.jail_net_traffic_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** The chosen period, the blocked filter and the order. */
@Composable
private fun Filters(state: NetworkActivityUiState, actions: NetworkActivityActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bucket = state.bucket
        val activity = state.activity
        if (bucket != null && activity != null) {
            InputChip(
                selected = true,
                onClick = { actions.onSelectBucket(null) },
                label = { Text(periodLabel(bucket, activity.bucketSeconds, activity.zone)) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
            )
        }
        FilterChip(
            selected = state.blockedOnly,
            onClick = { actions.onBlockedOnly(!state.blockedOnly) },
            label = { Text(stringResource(R.string.jail_net_filter_blocked_count, state.blockedCount)) },
            leadingIcon = if (state.blockedOnly) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
        )
        MenuFilterChip(
            label = stringResource(state.sort.label),
            options = DestinationSort.entries.map { MenuOption(it, stringResource(it.label)) },
            selected = state.sort,
            onSelect = actions.onSort,
        )
    }
}

private val DestinationSort.label: Int
    get() = when (this) {
        DestinationSort.DATA -> R.string.jail_net_sort_data
        DestinationSort.CONNECTIONS -> R.string.jail_net_sort_connections
        DestinationSort.RECENT -> R.string.jail_net_sort_recent
        DestinationSort.NAME -> R.string.jail_net_sort_name
    }

@Composable
private fun ShowAll(count: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.jail_net_show_all, count))
        Spacer(Modifier.size(6.dp))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null)
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

/** Tells of a block or unblock, with Undo, until it is undone or replaced. */
@Composable
internal fun MessageEffect(message: NetworkMessage?, snackbar: SnackbarHostState, actions: NetworkActivityActions) {
    MessageEffect(message, snackbar, actions.onUndo, actions.onMessageDismiss)
}

@Composable
internal fun MessageEffect(message: NetworkMessage?, snackbar: SnackbarHostState, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val text = message?.let {
        stringResource(if (it is NetworkMessage.Blocked) R.string.jail_net_blocked_message else R.string.jail_net_unblocked_message, it.target.name)
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
