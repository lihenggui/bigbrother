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

package li.mercury.bigbrother.feature.applist.impl

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.AppListItem
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.MenuFilterChip
import li.mercury.bigbrother.core.ui.MenuOption
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.Reveal
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.UserSettingsWriter
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.categoriesSummary
import li.mercury.bigbrother.core.ui.categoryDetail
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.rememberSettingsWriteSnackbar
import li.mercury.bigbrother.core.ui.shortTime
import li.mercury.bigbrother.core.ui.R as UiR

@Composable
fun AppListScreen(
    selectedUid: Int?,
    onOpenApp: (app: AppSummary, range: TimeRange, category: DataCategory?) -> Unit,
    viewModel: AppListViewModel,
) {
    var sort by rememberSaveable { mutableStateOf(SortOrder.SENSITIVITY) }
    var category by rememberSaveable { mutableStateOf<DataCategory?>(null) }
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val lists = remember(viewModel, resources, configuration) {
        viewModel.present(snapshotFlow { AppListFilter(sort, category) }, resources)
    }
    val presentation by lists.collectAsStateWithLifecycle(initialValue = AppListPresentation())
    // Records keep accumulating in the background, so reload on return.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose {}
    }
    AppListScreen(
        presentation = presentation,
        onSort = { sort = it },
        onCategory = { category = it },
        selectedUid = selectedUid,
        onRange = viewModel::setRange,
        onRefresh = viewModel::refresh,
        onOpen = onOpenApp,
        onShowSystemApps = viewModel::setShowSystemApps,
        onSettingsWriteResult = viewModel::onMessageShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppListScreen(
    presentation: AppListPresentation,
    onSort: (SortOrder) -> Unit,
    onCategory: (DataCategory?) -> Unit,
    selectedUid: Int?,
    onRange: (TimeRange) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (AppSummary, TimeRange, DataCategory?) -> Unit,
    onShowSystemApps: (Boolean) -> Unit,
    onSettingsWriteResult: (UserSettingsWriter.Failure, Boolean) -> Unit = { _, _ -> },
) {
    val (state, filter, groups, categories) = presentation
    val (sort, category) = filter
    val snackbar = rememberSettingsWriteSnackbar(state.message, onSettingsWriteResult)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            Reveal(state.failure == null && groups != null) {
                Filters(
                    range = state.range,
                    onRange = onRange,
                    present = categories,
                    category = category,
                    onCategory = onCategory,
                    sort = sort,
                    onSort = onSort,
                    showSystemApps = state.showSystemApps,
                    onShowSystemApps = onShowSystemApps,
                )
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing && state.apps != null,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                FadeThrough(presentation, kind = { ContentKind.of(it.state.failure, it.groups) }) { shown ->
                    val shownState = shown.state
                    val shownGroups = shown.groups
                    when {
                        shownState.failure != null -> LoadFailureMessage(shownState.failure, onRetry = onRefresh)

                        shownGroups == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())

                        else -> AppList(
                            groups = shownGroups,
                            appInfo = shownState.appInfo,
                            sort = shown.filter.sort,
                            category = shown.filter.category,
                            hiddenApps = shownState.hiddenApps,
                            showSystemApps = shownState.showSystemApps,
                            selectedUid = selectedUid,
                            onOpen = { app -> onOpen(app, shownState.range, shown.filter.category) },
                            onShowSystemApps = { onShowSystemApps(true) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The time range, the category and the order, each a chip that opens a
 * menu of choices. The system apps setting sits in the order's menu.
 */
@Composable
private fun Filters(
    range: TimeRange,
    onRange: (TimeRange) -> Unit,
    present: List<DataCategory>,
    category: DataCategory?,
    onCategory: (DataCategory?) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    showSystemApps: Boolean,
    onShowSystemApps: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuFilterChip(
            label = stringResource(range.label),
            options = TimeRange.entries.map { MenuOption(it, stringResource(it.label)) },
            selected = range,
            onSelect = onRange,
        )
        MenuFilterChip(
            label = category?.let { stringResource(it.title) } ?: stringResource(R.string.all_categories),
            options = listOf(MenuOption<DataCategory?>(null, stringResource(R.string.all_categories))) +
                present.map { MenuOption(it, stringResource(it.title)) },
            selected = category,
            onSelect = onCategory,
            highlighted = category != null,
        )
        MenuFilterChip(
            label = stringResource(sort.label),
            options = SortOrder.entries.map { MenuOption(it, stringResource(it.label)) },
            selected = sort,
            onSelect = onSort,
            extra = { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.show_system_apps)) },
                    trailingIcon = if (showSystemApps) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onShowSystemApps(!showSystemApps)
                        close()
                    },
                )
            },
        )
    }
}

@Composable
private fun AppList(
    groups: List<Pair<Int?, List<AppListEntry>>>,
    appInfo: Map<String, AppInfo>,
    sort: SortOrder,
    category: DataCategory?,
    hiddenApps: Int,
    showSystemApps: Boolean,
    selectedUid: Int?,
    onOpen: (AppSummary) -> Unit,
    onShowSystemApps: () -> Unit,
) {
    // A different filter or order is a different list: it starts at the top
    // instead of at whichever row happened to be first. The state is kept,
    // since it holds what animates the rows to their new places; a restored
    // screen keeps its position.
    val listState = rememberLazyListState()
    val filter = "$sort/$category/$showSystemApps"
    var listed by rememberSaveable { mutableStateOf(filter) }
    SideEffect {
        if (listed != filter) {
            listed = filter
            listState.requestScrollToItem(0)
        }
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (groups.all { (_, members) -> members.isEmpty() }) {
            item(key = "empty") {
                EmptyAppList(hiddenApps, category, onShowSystemApps, Modifier.animatedRow(this, MaterialTheme.motionScheme).fillParentMaxHeight(0.7f))
            }
        }
        for ((title, members) in groups) {
            if (title != null) {
                item(key = "header:$title") { SectionHeader(pluralStringResource(title, members.size, members.size), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
            }
            itemsIndexed(members, key = { _, entry -> entry.app.uid }) { index, entry ->
                AppListItem(
                    app = entry.app,
                    info = appInfo[entry.app.packageName],
                    // With a category selected, the row says what within it
                    // was accessed; otherwise, which categories.
                    supporting = if (category != null) {
                        categoryDetail(entry.app, category)
                    } else {
                        categoriesSummary(entry.categories)
                    },
                    trailing = shortTime(entry.app.lastSeen),
                    index = index,
                    count = members.size,
                    selected = entry.app.uid == selectedUid,
                    onClick = { onOpen(entry.app) },
                    modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
                )
            }
        }
    }
}

@Composable
private fun EmptyAppList(hiddenApps: Int, category: DataCategory?, onShowSystemApps: () -> Unit, modifier: Modifier = Modifier) {
    if (hiddenApps > 0 && category == null) {
        // There are records, but only for apps that are hidden.
        Message(
            title = stringResource(R.string.only_system_apps),
            body = "",
            modifier = modifier,
            actionLabel = stringResource(R.string.show_system_apps),
            onAction = onShowSystemApps,
        )
    } else {
        Message(stringResource(UiR.string.empty_title), "", modifier)
    }
}
