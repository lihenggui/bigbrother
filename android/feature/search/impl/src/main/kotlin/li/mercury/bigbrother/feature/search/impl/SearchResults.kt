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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.ui.AppListItem
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.VOLUME_ROOT
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.categoriesSummary
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.shortTime
import li.mercury.bigbrother.core.ui.topicIcon
import li.mercury.bigbrother.core.ui.topicTitle
import li.mercury.bigbrother.core.ui.R as UiR

internal sealed interface SearchDestination {
    data class App(val app: AppSummary, val category: DataCategory?) : SearchDestination
    data class Category(val category: DataCategory) : SearchDestination
}

@Composable
internal fun Results(
    results: SearchUiState,
    onResultClick: (SearchDestination) -> Unit,
    onRetry: () -> Unit,
) {
    FadeThrough(results, kind = { it.kind() }) { shown ->
        when (shown.kind()) {
            ContentKind.Failure -> LoadFailureMessage(checkNotNull(shown.failure), onRetry = onRetry)

            // The expanded bar stays empty until there is a query.
            ContentKind.Empty -> Unit

            else -> ResultList(shown, onResultClick)
        }
    }
}

private fun SearchUiState.kind(): ContentKind = when {
    failure != null -> ContentKind.Failure
    query.isEmpty() && complete -> ContentKind.Empty
    else -> ContentKind.Content
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultList(results: SearchUiState, onResultClick: (SearchDestination) -> Unit) {
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
            val label = when {
                !results.complete -> R.string.searching
                nothing -> UiR.string.no_matches
                else -> R.string.results
            }
            val spoken = stringResource(label)
            FadeThrough(
                state = label,
                kind = { it },
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme)
                    .padding(start = 16.dp, top = 8.dp)
                    // One node speaks for the label, the new one, also while the old one fades out.
                    .clearAndSetSemantics {
                        text = AnnotatedString(spoken)
                        liveRegion = LiveRegionMode.Polite
                    },
            ) { shown ->
                Text(
                    text = stringResource(shown),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (results.apps.isNotEmpty()) {
            item(key = "apps") { SectionHeader(stringResource(R.string.section_apps), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
            itemsIndexed(results.apps, key = { _, app -> "app:${app.uid}" }) { index, app ->
                AppListItem(
                    app = app,
                    info = results.appInfo[app.packageName],
                    supporting = categoriesSummary(app.dataCategories()),
                    trailing = shortTime(app.lastSeen),
                    index = index,
                    count = results.apps.size,
                    onClick = { onResultClick(SearchDestination.App(app, null)) },
                    modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
                )
            }
        }
        if (results.files.isNotEmpty()) {
            item(key = "files") { SectionHeader(stringResource(R.string.section_files), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
            itemsIndexed(results.files, key = { _, match -> "path:${match.uid}:${match.access.path}" }) { index, match ->
                FileResult(
                    match = match,
                    results = results,
                    index = index,
                    count = results.files.size,
                    onClick = { onResultClick(SearchDestination.App(match.toAppSummary(), DataCategory.FILES)) },
                    modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
                )
            }
        }
        if (results.data.isNotEmpty()) {
            item(key = "data") { SectionHeader(stringResource(R.string.section_data), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
            itemsIndexed(results.data, key = { _, match -> "data:${match.category}:${match.topic}" }) { index, match ->
                DataResult(match, index, results.data.size, onClick = { onResultClick(SearchDestination.Category(match.category)) }, modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme))
            }
        }
    }
}

private fun PathMatch.toAppSummary() = AppSummary(uid, packageName, emptyMap(), access.lastSeen)

/** A path: its name, then where it is and which app touched it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FileResult(match: PathMatch, results: SearchUiState, index: Int, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        modifier = modifier,
    )
}

/** A kind of data and how many apps accessed it; opens the page of its category. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DataResult(match: DataMatch, index: Int, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val title = match.topic?.let { topicTitle(it) } ?: match.category.title
    ListItem(
        onClick = onClick,
        modifier = modifier,
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
