package li.mercury.bigbrother.feature.timeline.impl

import android.text.format.DateUtils
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.ui.AppListItem
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.MenuFilterChip
import li.mercury.bigbrother.core.ui.MenuOption
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.namedList
import li.mercury.bigbrother.core.ui.R as UiR
import li.mercury.bigbrother.core.ui.topicTitle

@Composable
fun TimelineScreen(
    topBar: @Composable () -> Unit,
    selectedUid: Int?,
    onOpenEntry: (TimelineEntry, TimeRange) -> Unit,
    viewModel: TimelineViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose {}
    }
    TimelineScreen(uiState, topBar, selectedUid, viewModel::refresh, viewModel::loadMore, onOpenEntry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineScreen(
    state: TimelineUiState,
    topBar: @Composable () -> Unit,
    selectedUid: Int?,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenEntry: (TimelineEntry, TimeRange) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf<DataCategory?>(null) }
    var includeLookups by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = topBar,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            if (state.failure == null && state.entries != null) {
                Filters(category, { category = it }, includeLookups, { includeLookups = it })
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing && state.entries != null,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val entries = state.entries
                when {
                    state.failure != null -> LoadFailureMessage(state.failure, onRetry = onRefresh)
                    entries == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())
                    else -> Timeline(
                        state = state,
                        entries = entries,
                        filter = TimelineFilter(category, includeLookups),
                        selectedUid = selectedUid,
                        onLoadMore = onLoadMore,
                        onOpenEntry = onOpenEntry,
                    )
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
    entries: List<TimelineEntry>,
    filter: TimelineFilter,
    selectedUid: Int?,
    onLoadMore: () -> Unit,
    onOpenEntry: (TimelineEntry, TimeRange) -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val items = remember(entries, filter, state.appInfo, state.showSystemApps) {
        timelineItems(entries, filter, state.appInfo, state.showSystemApps, context.packageName) { ts ->
            Instant.ofEpochSecond(ts).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        }
    }
    val listState = rememberLazyListState()
    // Near the end, or with too little shown to scroll, the next page is loaded.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - LOAD_AHEAD
        }
    }
    LaunchedEffect(nearEnd, state.nextBefore, items.size) {
        if (nearEnd && state.nextBefore != null) onLoadMore()
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
                Message(stringResource(R.string.timeline_empty), "", Modifier.fillParentMaxHeight(0.7f))
            }
        }
        items(items, key = { it.key }) { item ->
            when (item) {
                is TimelineItem.Day -> Text(
                    text = dayLabel(item.dayStart),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier
                        .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
                is TimelineItem.Hour -> Text(
                    text = DateUtils.formatDateTime(context, item.hourStart * 1000, DateUtils.FORMAT_SHOW_TIME),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp),
                )
                is TimelineItem.Row -> {
                    val first = item.first
                    AppListItem(
                        app = first.toAppSummary(),
                        info = state.appInfo[first.packageName],
                        supporting = rowSummary(item.entries),
                        trailing = DateUtils.formatDateTime(context, item.lastSeen * 1000, DateUtils.FORMAT_SHOW_TIME),
                        index = item.index,
                        count = item.count,
                        // The app open beside the timeline is marked in every hour.
                        selected = selectedUid == first.uid,
                        badge = first.dataCategory.icon,
                        onClick = {
                            onOpenEntry(first, rangeCovering(first, System.currentTimeMillis() / 1000))
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        if (state.loadingMore) {
            item(key = "loading-more") {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                    LoadingIndicator()
                }
            }
        }
    }
}

/** Rows from the end at which the next page starts loading. */
private const val LOAD_AHEAD = 20

/** "Today", "Yesterday", or the date with its weekday. */
@Composable
private fun dayLabel(dayStart: Long): String {
    val context = LocalContext.current
    val millis = dayStart * 1000
    return when {
        DateUtils.isToday(millis) -> stringResource(R.string.today)
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.yesterday)
        else -> DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL,
        )
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

/** One entry in a few words: "Read 23 files · DCIM", "Android ID · 3 times". */
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
        else -> {
            val title = entry.topic?.let { topicTitle(it) }?.let { stringResource(it) }
                ?: stringResource(entry.dataCategory.title)
            val count = if (entry.allowed == 0) {
                pluralStringResource(R.plurals.times_denied, entry.denied, entry.denied)
            } else {
                pluralStringResource(R.plurals.times, entry.allowed, entry.allowed)
            }
            title + separator + count
        }
    }
}
