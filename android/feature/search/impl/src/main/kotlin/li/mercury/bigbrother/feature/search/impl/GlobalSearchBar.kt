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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.topicTitleIds
import li.mercury.bigbrother.core.ui.R as UiR

/** The shared search and settings entry point of the top-level screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchBar(
    onOpenApp: (app: AppSummary, range: TimeRange, category: DataCategory?) -> Unit,
    onOpenCategory: (DataCategory) -> Unit,
    onOpenSettings: () -> Unit,
    settingsBadge: Boolean,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GlobalSearchBar(
        state = state,
        onQueryChange = viewModel::setQuery,
        onResultClick = { destination ->
            when (destination) {
                is SearchDestination.App -> onOpenApp(destination.app, TimeRange.ALL, destination.category)
                is SearchDestination.Category -> onOpenCategory(destination.category)
            }
        },
        onOpenSettings = onOpenSettings,
        onRetry = viewModel::retry,
        settingsBadge = settingsBadge,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GlobalSearchBar(
    state: SearchUiState,
    onQueryChange: (String, Set<Int>) -> Unit,
    onResultClick: (SearchDestination) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    settingsBadge: Boolean,
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberTextFieldState(),
    searchBarState: SearchBarState = rememberSearchBarState(),
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val titles = remember(resources, configuration) {
        (DataCategory.entries.map { it.title } + topicTitleIds).associateWith(resources::getString)
    }
    val submitQuery by rememberUpdatedState(onQueryChange)
    LaunchedEffect(textFieldState, titles) {
        snapshotFlow { textFieldState.text.toString().trim() }.collect { text ->
            val titleIds = if (text.isEmpty()) emptySet() else titles.filterValues { it.contains(text, ignoreCase = true) }.keys
            submitQuery(text, titleIds)
        }
    }
    val close: () -> Unit = {
        // Navigation can remove the bar and cancel its animation.
        textFieldState.clearText()
        onQueryChange("", emptySet())
        scope.launch { searchBarState.animateToCollapsed() }
    }
    val inputField = @Composable {
        SearchInputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onClose = close,
            onExpand = { scope.launch { searchBarState.animateToExpanded() } },
        )
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
            val status = if (settingsBadge) stringResource(UiR.string.recording_stopped) else null
            IconButton(
                onClick = onOpenSettings,
                modifier = if (status != null) Modifier.semantics { stateDescription = status } else Modifier,
            ) {
                BadgedBox(badge = { if (settingsBadge) Badge() }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
        },
    )
    SearchResultsPanel(
        searchBarState = searchBarState,
        inputField = inputField,
        state = state,
        onResultClick = { destination ->
            close()
            onResultClick(destination)
        },
        onRetry = onRetry,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchInputField(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    onClose: () -> Unit,
    onExpand: () -> Unit,
) {
    val expanded = searchBarState.targetValue == SearchBarValue.Expanded
    val hint = stringResource(R.string.search_hint)
    Box {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = {},
            // A pane can move focus to its first field. Require a tap so this
            // doesn't open search when the pane comes into view.
            modifier = if (expanded) Modifier else Modifier.focusProperties { canFocus = false },
            placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = {
                if (expanded) {
                    IconButton(onClick = onClose) {
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
                    .clickable(role = Role.Button, onClickLabel = hint, onClick = onExpand)
                    // The field cannot take focus while collapsed.
                    .semantics { contentDescription = hint },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SearchResultsPanel(
    searchBarState: SearchBarState,
    inputField: @Composable () -> Unit,
    state: SearchUiState,
    onResultClick: (SearchDestination) -> Unit,
    onRetry: () -> Unit,
) {
    val content = @Composable { Results(state, onResultClick, onRetry) }
    val window = currentWindowAdaptiveInfoV2().windowSizeClass
    if (window.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
        ExpandedDockedSearchBar(state = searchBarState, inputField = inputField) { content() }
    } else {
        ExpandedFullScreenSearchBar(state = searchBarState, inputField = inputField) { content() }
    }
}
