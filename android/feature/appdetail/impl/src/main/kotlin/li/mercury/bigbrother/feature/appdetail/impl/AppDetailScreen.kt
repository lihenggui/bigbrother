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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.icon.CageIcons
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.failureTitle
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.shortTime
import li.mercury.bigbrother.core.ui.R as UiR

/**
 * Everything one app accessed, on one page: its files first, then each kind
 * of private data, then what the app is. Rows open the detail: the file
 * browser, or the record of one kind of data.
 */
@Composable
fun AppDetailScreen(
    focusCategory: String?,
    openRecord: String?,
    onBack: () -> Unit,
    onOpenFiles: (location: String?) -> Unit,
    onOpenRecord: (topic: String, source: String) -> Unit,
    onOpenJail: (packageName: String) -> Unit,
    viewModel: AppDetailViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val action = uiState.jailAction
    val openJail by rememberUpdatedState(onOpenJail)
    LaunchedEffect(action) {
        if (action is JailAction.Open) {
            viewModel.dismissJailAction()
            openJail(action.packageName)
        }
    }
    AppDetailScreen(
        state = uiState,
        focusCategory = focusCategory,
        openRecord = openRecord,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenFiles = onOpenFiles,
        onOpenRecord = onOpenRecord,
        onRequestJail = viewModel::requestJail,
        onConfirmIsolation = viewModel::confirmIsolation,
        onDismissJailAction = viewModel::dismissJailAction,
    )
}

/** One row of the page, in order. */
internal sealed interface Item {
    val key: String

    data class Header(val title: Int, val lastSeen: Long?, val category: DataCategory? = null) : Item {
        override val key: String get() = "header:${category?.name ?: title}"
    }

    data class Folder(val folder: FolderSummary, val index: Int, val count: Int) : Item {
        override val key: String get() = "folder:${folder.path}"
    }

    data class Collection(val access: Access, val index: Int, val count: Int) : Item {
        override val key: String get() = "collection:$index:${access.path}"
    }

    data class BrowseFiles(val totals: FileTotals, val index: Int, val count: Int) : Item {
        override val key: String get() = "browse"
    }

    data class Topic(val group: TopicGroup, val index: Int, val count: Int) : Item {
        override val key: String get() = "topic:${group.key}"
    }

    data class Info(val label: Int, val value: String, val index: Int, val count: Int) : Item {
        override val key: String get() = "info:$label"
    }

    data object Launch : Item {
        override val key: String get() = "launch"
    }
}

/** Folders named before the rest of the files are left to the browser. */
private const val FOLDERS_SHOWN = 3

internal class AppDetailRecords(val items: List<Item>, val empty: Boolean) {
    private val sectionIndices = items.mapIndexedNotNull { index, item ->
        (item as? Item.Header)?.category?.let { it.name to index }
    }.toMap()

    fun indexOf(category: String): Int? = sectionIndices[category]
}

internal fun summarizeAccesses(accesses: List<Access>): AppDetailRecords {
    val files = accesses.filter { it.isFile() }
    val folders = topFolders(files, FOLDERS_SHOWN)
    val totals = fileTotals(files)
    val collections = files.filter { it.category == Category.PROVIDER }
    val categories = groupByCategory(accesses)
    val items = buildList {
        if (files.isNotEmpty()) {
            add(Item.Header(DataCategory.FILES.title, files.maxOf { it.lastSeen }, DataCategory.FILES))
            val count = folders.size + collections.size + 1
            folders.forEachIndexed { i, folder -> add(Item.Folder(folder, i, count)) }
            collections.forEachIndexed { i, access -> add(Item.Collection(access, folders.size + i, count)) }
            add(Item.BrowseFiles(totals, count - 1, count))
        }
        for ((category, groups) in categories) {
            add(Item.Header(category.title, groups.maxOf { it.lastSeen }, category))
            groups.forEachIndexed { i, group -> add(Item.Topic(group, i, groups.size)) }
        }
    }
    return AppDetailRecords(items, accesses.isEmpty())
}

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppDetailScreen(
    state: AppDetailUiState,
    focusCategory: String?,
    openRecord: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenFiles: (location: String?) -> Unit,
    onOpenRecord: (topic: String, source: String) -> Unit,
    onRequestJail: () -> Unit,
    onConfirmIsolation: () -> Unit,
    onDismissJailAction: () -> Unit,
) {
    val context = LocalContext.current
    val app = state.app
    val name = appDisplayName(app, state.appInfo)
    JailActionDialog(state.jailAction, name, onConfirmIsolation, onDismissJailAction)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Grouped list items are surface-colored and sit on a tonal background,
    // which is what separates the groups.
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(state.appInfo, name, size = 40.dp)
                        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                },
                // The records cover the range chosen on the list; it is
                // stated here so the numbers below read correctly.
                subtitle = {
                    val range = stringResource(state.range.label)
                    Text(
                        text = if (app.packageName.isEmpty()) {
                            range
                        } else {
                            stringResource(R.string.header_subtitle, app.packageName, range)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // Beside the list, the list itself is the way back.
                navigationIcon = {
                    if (!LocalShowsDetailBesideList.current) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(UiR.string.back),
                            )
                        }
                    }
                },
                actions = {
                    if (state.canOpenJail) {
                        IconButton(onClick = onRequestJail, enabled = state.jailAction == null) {
                            Icon(CageIcons.Outlined, contentDescription = stringResource(R.string.open_jail))
                        }
                    }
                    if (state.packageDetails != null) {
                        IconButton(onClick = { context.startActivity(appSettingsIntent(app.packageName)) }) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.info_open_settings),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    scrolledContainerColor = background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val records = state.records
        when {
            state.failure != null -> LoadFailureMessage(state.failure, onRetry, Modifier.padding(padding))

            records == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize().padding(padding))

            else -> Summary(
                records = records,
                info = buildInfoItems(state),
                truncated = state.truncated,
                padding = padding,
                focusCategory = focusCategory,
                openRecord = openRecord,
                launchPackage = app.packageName,
                onOpenFiles = onOpenFiles,
                onOpenRecord = onOpenRecord,
            )
        }
    }
}

@Composable
private fun JailActionDialog(action: JailAction?, appName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (action) {
        JailAction.Confirm, JailAction.Isolating -> {
            val busy = action == JailAction.Isolating
            AlertDialog(
                onDismissRequest = { if (!busy) onDismiss() },
                title = { Text(stringResource(R.string.isolate_app_title, appName)) },
                text = { Text(stringResource(if (busy) R.string.isolating_app else R.string.isolate_app_summary)) },
                confirmButton = {
                    TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.isolate_app)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }

        is JailAction.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.jail_action_failed)) },
            text = {
                Text(
                    when (val failure = action.failure) {
                        is LoadFailure.Other -> stringResource(R.string.jail_action_retry)
                        null -> stringResource(R.string.jail_app_unavailable)
                        else -> failureTitle(failure)
                    },
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        )

        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Summary(
    records: AppDetailRecords,
    info: List<Item>,
    truncated: Boolean,
    padding: PaddingValues,
    focusCategory: String?,
    openRecord: String?,
    launchPackage: String,
    onOpenFiles: (location: String?) -> Unit,
    onOpenRecord: (topic: String, source: String) -> Unit,
) {
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
                Message(stringResource(UiR.string.empty_title), "", Modifier.fillParentMaxHeight(0.7f))
            }
        }
        fun itemAt(index: Int) = records.items.getOrNull(index) ?: info[index - records.items.size]
        items(records.items.size + info.size, key = { itemAt(it).key }) { index ->
            val item = itemAt(index)
            val modifier = Modifier.animatedRow(this, motion)
            when (item) {
                is Item.Header -> SectionHeader(
                    title = stringResource(item.title),
                    trailing = item.lastSeen?.let { stringResource(R.string.last_access, shortTime(it)) },
                    modifier = Modifier.padding(top = 8.dp),
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

                Item.Launch -> LaunchButton(launchPackage)
            }
        }
        if (truncated) {
            item(key = "truncated") {
                Text(
                    text = stringResource(R.string.results_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
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
private fun LaunchButton(packageName: String) {
    val context = LocalContext.current
    val launch = remember(packageName) { context.packageManager.getLaunchIntentForPackage(packageName) }
        ?: return
    OutlinedButton(
        onClick = { context.startActivity(launch) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(stringResource(R.string.info_launch))
    }
}
