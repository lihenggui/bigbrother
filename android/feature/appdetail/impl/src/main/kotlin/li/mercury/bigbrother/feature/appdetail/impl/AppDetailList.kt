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

package li.mercury.bigbrother.feature.appdetail.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.shortTime
import li.mercury.bigbrother.core.ui.R as UiR

@Composable
private fun buildInfoItems(state: AppDetailUiState): List<Item> {
    val info = infoRows(state.app, state.packageDetails)
    return buildList {
        if (info.isNotEmpty()) {
            add(Item.Header(R.string.tab_info, null))
            info.forEachIndexed { i, (label, value) -> add(Item.Info(label, value, i, info.size)) }
        }
        if (state.packageDetails != null) add(Item.Launch)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppDetailList(
    records: AppDetailRecords,
    state: AppDetailUiState,
    padding: PaddingValues,
    focusCategory: String?,
    openRecord: String?,
    onOpenFiles: (location: String?) -> Unit,
    onOpenRecord: (topic: String, source: String) -> Unit,
) {
    val info = buildInfoItems(state)
    val listState = rememberLazyListState()
    // Opened for one kind of data, the page starts at it, once.
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(records, focusCategory) {
        if (focused || focusCategory == null) return@LaunchedEffect
        records.indexOf(focusCategory)?.let { listState.scrollToItem(it) }
        focused = true
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val motion = MaterialTheme.motionScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding(),
            bottom = 24.dp + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (records.empty) {
            item(key = "empty") {
                Message(stringResource(UiR.string.empty_title), "", Modifier.animatedRow(this, motion).fillParentMaxHeight(0.7f))
            }
        }
        fun itemAt(index: Int) = records.items.getOrNull(index) ?: info[index - records.items.size]
        items(records.items.size + info.size, key = { itemAt(it).key }) { index ->
            val item = itemAt(index)
            val modifier = Modifier.animatedRow(this, motion)
            AppDetailRow(item, openRecord, state.app.packageName, onOpenFiles, onOpenRecord, modifier)
        }
        if (state.truncated) {
            item(key = "truncated") {
                Text(
                    text = stringResource(R.string.results_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.animatedRow(this, motion).padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AppDetailRow(
    item: Item,
    openRecord: String?,
    launchPackage: String,
    onOpenFiles: (location: String?) -> Unit,
    onOpenRecord: (topic: String, source: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is Item.Header -> SectionHeader(
            title = stringResource(item.title),
            trailing = item.lastSeen?.let { stringResource(R.string.last_access, shortTime(it)) },
            modifier = modifier.padding(top = 8.dp),
        )

        is Item.Folder -> FolderRow(item, onClick = { onOpenFiles(item.folder.path) }, modifier = modifier)

        is Item.Collection -> CollectionSummaryRow(item, onClick = { onOpenFiles(Location.MEDIA) }, modifier = modifier)

        is Item.BrowseFiles -> BrowseFilesRow(item, onClick = { onOpenFiles(null) }, modifier = modifier)

        is Item.Topic -> TopicRow(
            group = item.group,
            index = item.index,
            count = item.count,
            selected = openRecord == item.group.key,
            onOpen = { onOpenRecord(item.group.topic, item.group.source.name) },
            modifier = modifier,
        )

        is Item.Info -> InfoRow(item.label, item.value, item.index, item.count, modifier)

        Item.Launch -> LaunchButton(launchPackage, modifier)
    }
}

/** "**Read** 23 files": the verb in bold, since reading is what matters most. */
@Composable
private fun readFiles(count: Int) = buildAnnotatedString {
    val text = pluralStringResource(R.plurals.files_read, count, count)
    val verb = stringResource(R.string.action_read)
    val start = text.indexOf(verb)
    if (start < 0) {
        append(text)
    } else {
        append(text.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
            append(verb)
        }
        append(text.substring(start + verb.length))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderRow(item: Item.Folder, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        onClick = onClick,
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(item.index, item.count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
        supportingContent = { Text(readFiles(item.folder.filesRead)) },
        trailingContent = { Text(shortTime(item.folder.lastSeen), style = MaterialTheme.typography.labelMedium) },
    ) {
        Text(item.folder.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollectionSummaryRow(item: Item.Collection, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val access = item.access
    val label = collectionLabel(access.path)
    ListItem(
        onClick = onClick,
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(item.index, item.count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
        supportingContent = {
            when {
                access.allowed == 0 -> DeniedText(
                    pluralStringResource(R.plurals.total_blocked, access.denied, access.denied),
                )

                access.action == Action.QUERY -> Text(
                    pluralStringResource(R.plurals.access_query, access.allowed, access.allowed, access.items),
                )

                else -> Text(pluralStringResource(R.plurals.opened_via_media, access.allowed, access.allowed))
            }
        },
        trailingContent = { Text(shortTime(access.lastSeen), style = MaterialTheme.typography.labelMedium) },
    ) {
        Text(
            text = stringResource(R.string.media_collection, label?.let { stringResource(it) } ?: access.path),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BrowseFilesRow(item: Item.BrowseFiles, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val totals = item.totals
    val parts = buildList {
        if (totals.read > 0) add(pluralStringResource(R.plurals.files_read, totals.read, totals.read))
        if (totals.lookedFor > 0) {
            add(pluralStringResource(R.plurals.paths_looked_for, totals.lookedFor, totals.lookedFor))
        }
    }
    ListItem(
        onClick = onClick,
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(item.index, item.count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { Icon(Icons.Outlined.AccountTree, contentDescription = null) },
        supportingContent = parts.takeIf { it.isNotEmpty() }?.let {
            { Text(it.joinToString(stringResource(R.string.time_separator))) }
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(stringResource(R.string.browse_files))
    }
}

/** Opens the app, when it has a screen to open. */
@Composable
private fun LaunchButton(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val launch = remember(packageName) { context.packageManager.getLaunchIntentForPackage(packageName) }
        ?: return
    OutlinedButton(
        onClick = { context.startActivity(launch) },
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(stringResource(R.string.info_launch))
    }
}
