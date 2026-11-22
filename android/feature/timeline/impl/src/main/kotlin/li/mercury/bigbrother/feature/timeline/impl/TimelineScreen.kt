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

package li.mercury.bigbrother.feature.timeline.impl

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.ui.AppListItem
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.MenuFilterChip
import li.mercury.bigbrother.core.ui.MenuOption
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.dateWithWeekday
import li.mercury.bigbrother.core.ui.failureTitle
import li.mercury.bigbrother.core.ui.namedList
import li.mercury.bigbrother.core.ui.timeOfDay
import li.mercury.bigbrother.core.ui.topicRecordsSummary
import li.mercury.bigbrother.core.ui.topicTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import li.mercury.bigbrother.core.ui.R as UiR

@Composable
fun TimelineScreen(
    selectedUid: Int?,
    onOpenEntry: (TimelineEntry, TimeRange) -> Unit,
    viewModel: TimelineViewModel,
) {
    var category by rememberSaveable { mutableStateOf<DataCategory?>(null) }
    var includeLookups by rememberSaveable { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val lists = remember(viewModel, zone) {
        viewModel.present(snapshotFlow { TimelineFilter(category, includeLookups) }, zone)
    }
    val presentation by lists.collectAsStateWithLifecycle(initialValue = TimelinePresentation())
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose {}
    }
    TimelineScreen(
        presentation = presentation,
        selectedUid = selectedUid,
        onCategory = { category = it },
        onIncludeLookups = { includeLookups = it },
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpenEntry = onOpenEntry,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineScreen(
    presentation: TimelinePresentation,
    selectedUid: Int?,
    onCategory: (DataCategory?) -> Unit,
    onIncludeLookups: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenEntry: (TimelineEntry, TimeRange) -> Unit,
) {
    val (state, filter, items) = presentation
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            if (state.failure == null && state.entries != null) {
                Filters(filter.category, onCategory, filter.includeLookups, onIncludeLookups)
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing && state.entries != null,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                FadeThrough(presentation, kind = { ContentKind.of(it.state.failure, it.items) }) { shown ->
                    val shownState = shown.state
                    val shownItems = shown.items
                    when {
                        shownState.failure != null -> LoadFailureMessage(shownState.failure, onRetry = onRefresh)

                        shownItems == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())

                        else -> Timeline(
                            state = shownState,
                            items = shownItems,
                            selectedUid = selectedUid,
                            onLoadMore = onLoadMore,
                            onOpenEntry = onOpenEntry,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Filters(
    category: DataCategory?,
    onCategory: (DataCategory?) -> Unit,
    includeLookups: Boolean,
    onIncludeLookups: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuFilterChip(
            label = category?.let { stringResource(it.title) } ?: stringResource(R.string.all_data),
            options = listOf(MenuOption<DataCategory?>(null, stringResource(R.string.all_data))) +
                DataCategory.entries.map { MenuOption(it, stringResource(it.title)) },
            selected = category,
            onSelect = onCategory,
            highlighted = category != null,
        )
        MenuFilterChip(
            label = stringResource(if (includeLookups) R.string.records_all else R.string.records_access),
            options = listOf(
                MenuOption(false, stringResource(R.string.records_access)),
                MenuOption(true, stringResource(R.string.records_all)),
            ),
            selected = includeLookups,
            onSelect = onIncludeLookups,
            highlighted = includeLookups,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Timeline(
    state: TimelineUiState,
    items: List<TimelineItem>,
    selectedUid: Int?,
    onLoadMore: () -> Unit,
    onOpenEntry: (TimelineEntry, TimeRange) -> Unit,
) {
    val listState = rememberLazyListState()
    // Near the end, or with too little shown to scroll, the next page is loaded.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - LOAD_AHEAD
        }
    }
    LaunchedEffect(nearEnd, state.nextBefore, items.size, state.loadMoreFailure) {
        if (nearEnd && state.nextBefore != null && state.loadMoreFailure == null) onLoadMore()
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (items.isEmpty() && state.nextBefore == null) {
            item(key = "empty") {
                Message(stringResource(R.string.timeline_empty), "", Modifier.animatedRow(this, MaterialTheme.motionScheme).fillParentMaxHeight(0.7f))
            }
        }
        items(items, key = { it.key }) { item ->
            TimelineRow(item, state.appInfo, selectedUid, onOpenEntry)
        }
        pagingFooter(state, onLoadMore)
    }
}

@Composable
private fun LazyItemScope.TimelineRow(
    item: TimelineItem,
    appInfo: Map<String, AppInfo>,
    selectedUid: Int?,
    onOpenEntry: (TimelineEntry, TimeRange) -> Unit,
) {
    val context = LocalContext.current
    val modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme)
    when (item) {
        is TimelineItem.Day -> Text(
            text = dayLabel(item.dayStart),
            style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = modifier
                .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                .semantics { heading() },
        )

        is TimelineItem.Hour -> Text(
            text = timeOfDay(context, item.hourStart),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp),
        )

        is TimelineItem.Row -> {
            val first = item.first
            AppListItem(
                app = first.toAppSummary(),
                info = appInfo[first.packageName],
                supporting = rowSummary(item.entries),
                trailing = timeOfDay(context, item.lastSeen),
                index = item.index,
                count = item.count,
                // The app open beside the timeline is marked in every hour.
                selected = selectedUid == first.uid,
                badge = first.dataCategory.icon,
                onClick = {
                    onOpenEntry(first, rangeCovering(first, System.currentTimeMillis() / 1000))
                },
                modifier = modifier,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.pagingFooter(state: TimelineUiState, onLoadMore: () -> Unit) {
    if (state.loadingMore) {
        item(key = "loading-more") {
            Row(Modifier.animatedRow(this, MaterialTheme.motionScheme).fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                LoadingIndicator()
            }
        }
    }
    if (state.loadMoreFailure != null) {
        item(key = "load-more-failed") {
            Row(
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme).fillMaxWidth().padding(start = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = failureTitle(state.loadMoreFailure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onLoadMore) { Text(stringResource(UiR.string.retry)) }
            }
        }
    }
}

/** Rows from the end at which the next page starts loading. */
private const val LOAD_AHEAD = 20

/** "Today", "Yesterday", or the date with its weekday. */
@Composable
private fun dayLabel(dayStart: Long): String {
    // By calendar date: a day is not always 24 hours long.
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochSecond(dayStart).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> dateWithWeekday(dayStart)
    }
}

/**
 * What an app did in an hour, most sensitive first: the whole of it when it
 * is one kind of data ("Read 23 files · DCIM"), otherwise the names of the
 * first kinds ("Location, Installed apps and 2 more").
 */
@Composable
private fun rowSummary(entries: List<TimelineEntry>): String {
    if (entries.size == 1) return entrySummary(entries.single())
    return namedList(entries.map { entryName(it) }.distinct()).orEmpty()
}

/** The name of what an entry is about: its files, the media library, or its topic. */
@Composable
private fun entryName(entry: TimelineEntry): String = when (entry.category) {
    Category.STORAGE -> if (entry.filesRead > 0) {
        pluralStringResource(R.plurals.read_files, entry.filesRead, entry.filesRead)
    } else {
        pluralStringResource(R.plurals.looked_for_paths, entry.paths, entry.paths)
    }

    Category.PROVIDER -> stringResource(UiR.string.summary_media_library)

    else -> entry.topic?.let { topicTitle(it) }?.let { stringResource(it) } ?: stringResource(entry.dataCategory.title)
}

/** The topic id of sensors. */
private const val SENSORS = "sensors"

/**
 * One entry in a few words: "Read 23 files · DCIM", "Android ID · 3 times",
 * "Clipboard · Read 2× · Written 1×", "Accelerometer · 4×".
 */
@Composable
private fun entrySummary(entry: TimelineEntry): String {
    val separator = stringResource(UiR.string.dot_separator)
    return when (entry.category) {
        Category.STORAGE -> {
            val what = if (entry.filesRead > 0) {
                pluralStringResource(R.plurals.read_files, entry.filesRead, entry.filesRead)
            } else {
                pluralStringResource(R.plurals.looked_for_paths, entry.paths, entry.paths)
            }
            listOfNotNull(what, entry.folder).joinToString(separator)
        }

        Category.PROVIDER -> when {
            entry.allowed == 0 -> pluralStringResource(R.plurals.media_denied, entry.denied, entry.denied)
            entry.isQuery -> stringResource(R.string.media_queried)
            else -> pluralStringResource(R.plurals.media_opened, entry.paths, entry.paths)
        }

        // Sensors are named by the sensors turned on, which say what they are.
        else -> {
            val records = topicRecordsSummary(entry.topic, entry.subjects, compact = true)
            if (records != null && entry.topic == SENSORS) return records
            val title = entry.topic?.let { topicTitle(it) }?.let { stringResource(it) }
                ?: stringResource(entry.dataCategory.title)
            if (records != null) return title + separator + records
            val count = if (entry.allowed == 0) {
                pluralStringResource(R.plurals.times_denied, entry.denied, entry.denied)
            } else {
                pluralStringResource(R.plurals.times, entry.allowed, entry.allowed)
            }
            title + separator + count
        }
    }
}
