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

package li.mercury.bigbrother.feature.search.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExpandedDockedSearchBar
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.AppListItem
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.VOLUME_ROOT
import li.mercury.bigbrother.core.ui.categoriesSummary
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.shortTime
import li.mercury.bigbrother.core.ui.topicIcon
import li.mercury.bigbrother.core.ui.topicTitle
import li.mercury.bigbrother.core.ui.R as UiR

/**
 * The top bar of the top-level screens: a search field in place of a title,
 * since search is the way into the records for anything specific, and the
 * settings. Searching covers apps, the paths they touched and the kinds of
 * data; results open full screen in compact windows and below the field in
 * wider ones.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3AdaptiveApi::class,
)
@Composable
fun GlobalSearchBar(
    onOpenApp: (app: AppSummary, range: TimeRange, category: DataCategory?) -> Unit,
    onOpenCategory: (DataCategory) -> Unit,
    onOpenSettings: () -> Unit,
    settingsBadge: Boolean,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect(viewModel::setQuery)
    }
    // Opening a result leaves the search, as a fresh start for the next one.
    val close: () -> Unit = {
        scope.launch {
            searchBarState.animateToCollapsed()
            textFieldState.clearText()
        }
    }
    val inputField = @Composable {
        val expanded = searchBarState.targetValue == SearchBarValue.Expanded
        val hint = stringResource(R.string.search_hint)
        Box {
            SearchBarDefaults.InputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = {},
                // Collapsed, the field takes no focus: a pane that comes into
                // view may move focus to its first field, which would open
                // the search uninvited. A tap opens it instead.
                modifier = if (expanded) Modifier else Modifier.focusProperties { canFocus = false },
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    if (expanded) {
                        IconButton(onClick = close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.close_search))
                        }
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    }
                },
                trailingIcon = if (expanded && textFieldState.text.isNotEmpty()) {
                    {
                        IconButton(onClick = { textFieldState.clearText() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                        }
                    }
                } else {
                    null
                },
            )
            if (!expanded) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(role = Role.Button, onClickLabel = hint) {
                            scope.launch { searchBarState.animateToExpanded() }
                        },
                )
            }
        }
    }
    // The bar takes the color of the screens below it; the field stands two
    // tones off it, as Material asks.
    val page = MaterialTheme.colorScheme.surfaceContainer
    AppBarWithSearch(
        state = searchBarState,
        inputField = inputField,
        modifier = modifier,
        colors = SearchBarDefaults.appBarWithSearchColors(
            searchBarColors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            appBarContainerColor = page,
            scrolledAppBarContainerColor = page,
        ),
        actions = {
            IconButton(onClick = onOpenSettings) {
                BadgedBox(badge = { if (settingsBadge) Badge() }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
        },
    )
    val content: @Composable () -> Unit = {
        Results(
            results = results,
            onOpenApp = { app, category ->
                close()
                onOpenApp(app, TimeRange.ALL, category)
            },
            onOpenCategory = {
                close()
                onOpenCategory(it)
            },
        )
    }
    // Material puts results full screen in compact windows and in a panel
    // below the field in wider ones.
    if (!currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
        ExpandedFullScreenSearchBar(state = searchBarState, inputField = inputField) { content() }
    } else {
        ExpandedDockedSearchBar(state = searchBarState, inputField = inputField) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Results(
    results: SearchResults,
    onOpenApp: (AppSummary, DataCategory?) -> Unit,
    onOpenCategory: (DataCategory) -> Unit,
) {
    // Nothing typed, or the first search still running with nothing to show.
    if (results.query.isEmpty() && results.complete) return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        val nothing = results.apps.isEmpty() && results.files.isEmpty() && results.data.isEmpty()
        // A label says that what follows are results, as Material asks for
        // focused search; it also announces when nothing matched. While a
        // new text is looked up, the previous results stay, but the label
        // does not speak for them.
        item(key = "label") {
            Text(
                text = when {
                    !results.complete -> stringResource(R.string.searching)
                    nothing -> stringResource(UiR.string.no_matches)
                    else -> stringResource(R.string.results)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 16.dp, top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (results.apps.isNotEmpty()) {
            item(key = "apps") { SectionHeader(stringResource(R.string.section_apps)) }
            itemsIndexed(results.apps, key = { _, app -> "app:${app.uid}" }) { index, app ->
                AppListItem(
                    app = app,
                    info = results.appInfo[app.packageName],
                    supporting = categoriesSummary(app.dataCategories()),
                    trailing = shortTime(app.lastSeen),
                    index = index,
                    count = results.apps.size,
                    onClick = { onOpenApp(app, null) },
                )
            }
        }
        if (results.files.isNotEmpty()) {
            item(key = "files") { SectionHeader(stringResource(R.string.section_files)) }
            itemsIndexed(results.files, key = { _, match -> "path:${match.uid}:${match.access.path}" }) { index, match ->
                FileResult(match, results, index, results.files.size) {
                    onOpenApp(match.toAppSummary(), DataCategory.FILES)
                }
            }
        }
        if (results.data.isNotEmpty()) {
            item(key = "data") { SectionHeader(stringResource(R.string.section_data)) }
            itemsIndexed(results.data, key = { _, match -> "data:${match.category}:${match.topic}" }) { index, match ->
                DataResult(match, index, results.data.size) { onOpenCategory(match.category) }
            }
        }
    }
}

private fun PathMatch.toAppSummary() = AppSummary(uid, packageName, emptyMap(), access.lastSeen)

/** A path: its name, then where it is and which app touched it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FileResult(match: PathMatch, results: SearchResults, index: Int, count: Int, onClick: () -> Unit) {
    val app = match.toAppSummary()
    val path = match.access.path
    AppListItem(
        app = app,
        info = results.appInfo[match.packageName],
        // Below the storage volume, the path says where the file is in terms
        // a file manager uses.
        supporting = path.replaceFirst(VOLUME_ROOT, "").removePrefix("/").ifEmpty { path },
        trailing = shortTime(match.access.lastSeen),
        index = index,
        count = count,
        badge = DataCategory.FILES.icon,
        onClick = onClick,
    )
}

/** A kind of data and how many apps accessed it; opens the page of its category. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DataResult(match: DataMatch, index: Int, count: Int, onClick: () -> Unit) {
    val title = match.topic?.let { topicTitle(it) } ?: match.category.title
    ListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { ShapedIcon(if (match.topic != null) topicIcon(match.topic) else match.category.icon) },
        supportingContent = {
            val apps = pluralStringResource(UiR.plurals.app_count, match.apps, match.apps)
            Text(
                if (match.topic != null) {
                    stringResource(match.category.title) + stringResource(UiR.string.dot_separator) + apps
                } else {
                    apps
                },
            )
        },
    ) {
        Text(stringResource(title))
    }
}
