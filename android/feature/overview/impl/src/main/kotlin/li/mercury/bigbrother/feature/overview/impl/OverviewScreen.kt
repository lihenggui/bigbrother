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

package li.mercury.bigbrother.feature.overview.impl

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.data.repository.ModuleUpdate
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.BigBrotherPullToRefreshBox
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.InlineMessage
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.TimeRangeButtonGroup
import li.mercury.bigbrother.core.ui.UserSettingsWriter
import li.mercury.bigbrother.core.ui.namedList
import li.mercury.bigbrother.core.ui.rememberSettingsWriteSnackbar
import li.mercury.bigbrother.core.ui.topicTitle
import li.mercury.bigbrother.core.ui.R as UiR

@Composable
fun OverviewScreen(
    onOpenCategory: (DataCategory) -> Unit,
    viewModel: OverviewViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The app counts as started once the overview shows records, or why it
    // can't (`am start -W`, startup benchmarks).
    ReportDrawnWhen { uiState.files != null || uiState.failure != null }
    // Records keep accumulating in the background, so reload on return.
    LifecycleResumeEffect(Unit) {
        viewModel.onScreenResumed()
        onPauseOrDispose { viewModel.onScreenPaused() }
    }
    OverviewScreen(
        state = uiState,
        onRange = viewModel::setRange,
        onRefresh = viewModel::refresh,
        onOpenCategory = onOpenCategory,
        onInstallModule = viewModel::installModule,
        onUpdateModule = viewModel::updateModule,
        onStartDaemon = viewModel::startDaemon,
        onSettingsWriteResult = viewModel::onMessageShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverviewScreen(
    state: OverviewUiState,
    onRange: (TimeRange) -> Unit,
    onRefresh: () -> Unit,
    onOpenCategory: (DataCategory) -> Unit,
    onInstallModule: () -> Unit,
    onUpdateModule: () -> Unit,
    onStartDaemon: () -> Unit,
    onSettingsWriteResult: (UserSettingsWriter.Failure, Boolean) -> Unit = { _, _ -> },
) {
    val snackbar = rememberSettingsWriteSnackbar(state.message, onSettingsWriteResult)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val files = state.files
        BigBrotherPullToRefreshBox(
            isRefreshing = state.refreshing && files != null,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            FadeThrough(state, kind = { it.kind() }) { shown ->
                val shownFiles = shown.files
                when (shown.kind()) {
                    OverviewKind.ModuleSetup -> ModuleSetupMessage(shown.moduleSetup, shown.modulePendingReboot, onInstallModule)
                    OverviewKind.ModuleUpdate -> ModuleUpdateCard(shown.moduleUpdate, onUpdateModule, onStartDaemon)
                    OverviewKind.Failure -> LoadFailureMessage(checkNotNull(shown.failure), onRetry = onRefresh)
                    OverviewKind.Loading -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())
                    OverviewKind.Content -> Content(shown, checkNotNull(shownFiles), onRange, onOpenCategory, onUpdateModule, onStartDaemon)
                }
            }
        }
    }
}

@Composable
private fun Content(
    state: OverviewUiState,
    files: FilesOverview,
    onRange: (TimeRange) -> Unit,
    onOpenCategory: (DataCategory) -> Unit,
    onUpdateModule: () -> Unit,
    onStartDaemon: () -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wider windows use the room for columns rather than longer lines.
        val columns = when {
            maxWidth >= 1200.dp -> 3
            maxWidth >= 600.dp -> 2
            else -> 1
        }
        val margin = if (columns == 1) 16.dp else 24.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = margin, end = margin, bottom = 24.dp + bottomInset),
        ) {
            RecorderStatusCards(state, onUpdateModule, onStartDaemon)
            TimeRangeButtonGroup(
                range = state.range,
                onRange = onRange,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            FilesCard(files, state.appInfo, wide = columns > 1, onClick = { onOpenCategory(DataCategory.FILES) })
            OtherCategories(state.categories, empty = files.readers.isEmpty() && files.lookedOnly == 0, columns, onOpenCategory)
        }
    }
}

/** What the screen shows at the top level: a setup step, a failure, the loader or the records. */
private enum class OverviewKind { ModuleSetup, ModuleUpdate, Failure, Loading, Content }

private fun OverviewUiState.kind(): OverviewKind = when {
    failure == LoadFailure.ModuleMissing -> OverviewKind.ModuleSetup
    failure == LoadFailure.ModuleOutdated && moduleUpdate != ModuleUpdate.Idle -> OverviewKind.ModuleUpdate
    failure != null -> OverviewKind.Failure
    files == null -> OverviewKind.Loading
    else -> OverviewKind.Content
}

/**
 * The categories other than files, or, when nothing at all was recorded in
 * the range, a message; another range can fade from one to the other.
 */
@Composable
private fun OtherCategories(categories: List<CategoryOverview>, empty: Boolean, columns: Int, onOpenCategory: (DataCategory) -> Unit) {
    FadeThrough(
        state = categories to empty,
        kind = { (shown, nothing) ->
            when {
                shown.isNotEmpty() -> ContentKind.Content
                nothing -> ContentKind.Empty
                else -> null
            }
        },
    ) { (shown, nothing) ->
        if (shown.isNotEmpty()) {
            Column {
                SectionHeader(
                    title = stringResource(R.string.other_categories),
                    trailing = pluralStringResource(R.plurals.category_count, shown.size, shown.size),
                    modifier = Modifier.padding(top = 8.dp),
                )
                CategoryColumns(shown, columns, onOpenCategory)
            }
        } else if (nothing) {
            // The column scrolls, so the message must not: see InlineMessage.
            InlineMessage(stringResource(UiR.string.empty_title), "", Modifier.padding(top = 24.dp))
        }
    }
}

/** The categories in [columns] segmented groups side by side, filled column by column. */
@Composable
private fun CategoryColumns(categories: List<CategoryOverview>, columns: Int, onOpen: (DataCategory) -> Unit) {
    val perColumn = (categories.size + columns - 1) / columns
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        categories.chunked(perColumn).forEach { chunk ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                chunk.forEachIndexed { index, category ->
                    CategoryRow(category, index, chunk.size) { onOpen(category.category) }
                }
            }
        }
        // Keeps the last column as wide as the others when there are fewer chunks.
        repeat(columns - (categories.size + perColumn - 1) / perColumn) { Spacer(Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryRow(category: CategoryOverview, index: Int, count: Int, onClick: () -> Unit) {
    // A topic named like its category ("Contacts") would only repeat the title.
    val title = stringResource(category.category.title)
    val topics = namedList(
        category.topics.map { topic -> topicTitle(topic)?.let { stringResource(it) } ?: topic }.filter { it != title },
    )
    val apps = pluralStringResource(UiR.plurals.app_count, category.apps, category.apps)
    ListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { ShapedIcon(category.category.icon) },
        supportingContent = {
            Text(
                text = listOfNotNull(topics, apps).joinToString(stringResource(UiR.string.dot_separator)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Text(title)
    }
}
