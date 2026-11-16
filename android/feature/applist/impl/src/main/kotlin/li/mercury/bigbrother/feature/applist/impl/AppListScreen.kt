package li.mercury.bigbrother.feature.applist.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.SearchTopBar
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.compactTime
import li.mercury.bigbrother.core.ui.topicTitle
import li.mercury.bigbrother.core.ui.R as UiR

/** How the app list is ordered. */
private enum class SortOrder(val label: Int) {
    /** Apps that touched the user's files first, most files first. */
    FILES(R.string.sort_files),
    RECENT(R.string.sort_recent),
    NAME(R.string.sort_name),
}

@Composable
fun AppListScreen(
    navigateToAppDetail: (uid: Int, packageName: String, range: TimeRange) -> Unit,
    viewModel: AppListViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Records keep accumulating in the background, so reload on return.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    AppListScreen(
        state = uiState,
        onRange = viewModel::setRange,
        onRefresh = viewModel::refresh,
        onOpen = { app -> navigateToAppDetail(app.uid, app.packageName, uiState.range) },
        onInstallModule = viewModel::installModule,
        onShowSystemApps = viewModel::setShowSystemApps,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppListScreen(
    state: AppListUiState,
    onRange: (TimeRange) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (AppSummary) -> Unit,
    onInstallModule: () -> Unit,
    onShowSystemApps: (Boolean) -> Unit,
) {
    // System apps are hidden unless asked for. An app whose details are
    // unknown (uninstalled, isolated process) is not assumed to be one.
    val visibleApps = remember(state.apps, state.appInfo, state.showSystemApps) {
        state.apps?.filter {
            state.showSystemApps || state.appInfo[it.packageName]?.isSystem != true
        }
    }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortOrder.FILES) }
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(enabled = searching, onBack = closeSearch)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // Segmented list items are surface-coloured and are meant to sit on a
    // tonal background, which is what separates the groups visually.
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            if (searching) {
                SearchTopBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.search_apps),
                    onClose = closeSearch,
                )
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.app_list_title)) },
                    subtitle = state.recording?.let { recording ->
                        {
                            StatusLine(
                                recording = recording,
                                unavailable = state.unavailable,
                                appsWithFiles = visibleApps.orEmpty().count { it.readUserFiles() },
                                range = state.range,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(UiR.string.search),
                            )
                        }
                        OptionsMenu(
                            range = state.range,
                            onRange = onRange,
                            sort = sort,
                            onSort = { sort = it },
                            showSystemApps = state.showSystemApps,
                            onShowSystemApps = onShowSystemApps,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = background,
                        scrolledContainerColor = background,
                    ),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing && state.apps != null,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val apps = visibleApps
            when {
                state.failure == LoadFailure.ModuleMissing ->
                    ModuleSetupMessage(state.moduleSetup, onInstallModule, onRefresh)
                state.failure != null -> LoadFailureMessage(state.failure, onRetry = onRefresh)
                apps == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())
                else -> AppList(
                    apps = apps,
                    appInfo = state.appInfo,
                    query = query,
                    sort = sort,
                    moduleOutdated = state.moduleOutdated,
                    modulePendingReboot = state.modulePendingReboot,
                    hiddenSystemApps = state.apps.orEmpty().size - apps.size,
                    onOpen = onOpen,
                    onInstallModule = onInstallModule,
                    onShowSystemApps = { onShowSystemApps(true) },
                )
            }
        }
    }
}

/**
 * Under the title: the headline answer for the selected time range, or a
 * warning if nothing is being recorded.
 */
@Composable
private fun StatusLine(
    recording: Boolean,
    unavailable: List<String>,
    appsWithFiles: Int,
    range: TimeRange,
) {
    if (!recording) {
        Text(stringResource(R.string.status_stopped), color = MaterialTheme.colorScheme.error)
        return
    }
    val summary = if (appsWithFiles == 0) {
        stringResource(R.string.status_none)
    } else {
        pluralStringResource(R.plurals.status_summary, appsWithFiles, appsWithFiles)
    }
    Column {
        // The range is chosen in the menu, so the title says which one applies.
        Text(
            if (range == TimeRange.ALL) {
                summary
            } else {
                stringResource(R.string.status_in_range, summary, stringResource(range.label))
            },
        )
        // Without this, an empty list would read as "nothing happened" for
        // kinds of activity that were never looked for.
        if (unavailable.isNotEmpty()) {
            val names = unavailable.map { id -> featureLabel(id)?.let { stringResource(it) } ?: id }
            Text(
                stringResource(
                    R.string.status_unavailable,
                    names.joinToString(stringResource(R.string.summary_separator)),
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Name of a kind of activity the daemon reports as `coverage_<id>`. */
private fun featureLabel(id: String): Int? = when (id) {
    "exec" -> R.string.feature_exec
    "network" -> R.string.feature_network
    "process" -> R.string.feature_process
    "netlink" -> R.string.feature_netlink
    "binder" -> R.string.feature_binder
    "appops" -> R.string.feature_appops
    else -> null
}

private val TimeRange.label: Int
    get() = when (this) {
        TimeRange.HOUR -> R.string.range_hour
        TimeRange.DAY -> R.string.range_day
        TimeRange.WEEK -> R.string.range_week
        TimeRange.ALL -> R.string.range_all
    }

/** Time range, sort order and the system apps switch. */
@Composable
private fun OptionsMenu(
    range: TimeRange,
    onRange: (TimeRange) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    showSystemApps: Boolean,
    onShowSystemApps: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuHeading(R.string.menu_time_range)
            for (option in TimeRange.entries) {
                DropdownMenuItem(
                    // The names are written to follow a sentence; here they stand alone.
                    text = { Text(stringResource(option.label).replaceFirstChar { it.uppercase() }) },
                    leadingIcon = { RadioButton(selected = option == range, onClick = null) },
                    onClick = {
                        onRange(option)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            MenuHeading(R.string.sort)
            for (order in SortOrder.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(order.label)) },
                    leadingIcon = { RadioButton(selected = order == sort, onClick = null) },
                    onClick = {
                        onSort(order)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.show_system_apps)) },
                leadingIcon = { Checkbox(checked = showSystemApps, onCheckedChange = null) },
                onClick = {
                    onShowSystemApps(!showSystemApps)
                    expanded = false
                },
            )
        }
    }
}

/** Names a group of options inside the menu. */
@Composable
private fun MenuHeading(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Shown when the module is absent: offers to flash the copy bundled in this APK. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModuleSetupMessage(
    setup: ModuleSetup,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
    when (setup) {
        ModuleSetup.Idle -> Message(
            title = stringResource(UiR.string.no_module_title),
            body = stringResource(UiR.string.no_module_body),
            actionLabel = stringResource(R.string.install_module),
            onAction = onInstall,
        )
        ModuleSetup.Installing -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoadingIndicator()
            Text(stringResource(R.string.installing_module))
        }
        ModuleSetup.NeedsReboot -> Message(
            title = stringResource(R.string.reboot_title),
            body = stringResource(R.string.reboot_body),
            actionLabel = stringResource(UiR.string.retry),
            onAction = onRetry,
        )
        is ModuleSetup.Failed -> Message(
            title = stringResource(R.string.install_failed_title),
            body = setup.output,
            actionLabel = stringResource(UiR.string.retry),
            onAction = onInstall,
        )
    }
}

@Composable
private fun AppList(
    apps: List<AppSummary>,
    appInfo: Map<String, AppInfo>,
    query: String,
    sort: SortOrder,
    moduleOutdated: Boolean,
    modulePendingReboot: Boolean,
    hiddenSystemApps: Int,
    onOpen: (AppSummary) -> Unit,
    onInstallModule: () -> Unit,
    onShowSystemApps: () -> Unit,
) {
    val isolated = stringResource(UiR.string.isolated_process)
    val nameOf = { app: AppSummary ->
        appInfo[app.packageName]?.label ?: app.packageName.ifEmpty { isolated }
    }
    val matching = remember(apps, appInfo, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            apps
        } else {
            apps.filter {
                nameOf(it).contains(needle, ignoreCase = true) ||
                    it.packageName.contains(needle, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (modulePendingReboot) {
            item(key = "restart") { ModuleNotice(stringResource(R.string.module_pending_reboot)) }
        } else if (moduleOutdated) {
            item(key = "update") {
                ModuleNotice(
                    text = stringResource(R.string.module_outdated),
                    actionLabel = stringResource(R.string.update_module),
                    onAction = onInstallModule,
                )
            }
        }
        if (matching.isEmpty()) {
            item(key = "empty") {
                val modifier = Modifier.fillParentMaxHeight(0.7f)
                when {
                    query.isNotBlank() -> Message(stringResource(UiR.string.no_matches), "", modifier)
                    // There are records, but only for apps that are hidden.
                    hiddenSystemApps > 0 -> Message(
                        title = stringResource(R.string.only_system_apps),
                        body = "",
                        modifier = modifier,
                        actionLabel = stringResource(R.string.show_system_apps),
                        onAction = onShowSystemApps,
                    )
                    else -> Message(
                        title = stringResource(UiR.string.empty_title),
                        body = stringResource(R.string.empty_body),
                        modifier = modifier,
                    )
                }
            }
        }
        when (sort) {
            SortOrder.FILES -> {
                // Apps that read the user's files, directly or through the
                // media library, form the first group. The rest (files only
                // checked for or refused, device information) keep the
                // daemon's order: most recent first.
                val (readFiles, others) = matching.partition { it.readUserFiles() }
                appSection(
                    R.string.section_files,
                    readFiles.sortedByDescending { it.filesRead + it.count(Category.PROVIDER) },
                    appInfo,
                    onOpen,
                )
                appSection(R.string.section_other, others, appInfo, onOpen)
            }
            SortOrder.RECENT -> appSection(null, matching, appInfo, onOpen)
            SortOrder.NAME -> appSection(
                null,
                matching.sortedBy { nameOf(it).lowercase() },
                appInfo,
                onOpen,
            )
        }
    }
}

/** Whether the app got at the user's own files: opened some, or went through the media library. */
private fun AppSummary.readUserFiles(): Boolean = filesRead > 0 || count(Category.PROVIDER) > 0

/** A group of apps drawn as one segmented list, with an optional title. */
private fun LazyListScope.appSection(
    title: Int?,
    apps: List<AppSummary>,
    appInfo: Map<String, AppInfo>,
    onOpen: (AppSummary) -> Unit,
) {
    if (apps.isEmpty()) return
    if (title != null) {
        item(key = "header-$title") {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
            )
        }
    }
    itemsIndexed(apps, key = { _, app -> app.uid }) { index, app ->
        AppRow(app, appInfo[app.packageName], index, apps.size, onClick = { onOpen(app) })
    }
}

/** A notice about the module at the top of the list, with an optional action. */
@Composable
private fun ModuleNotice(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppSummary,
    info: AppInfo?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val baseName = appDisplayName(app, info)
    // The same app can run as several Android users (clones, work profile);
    // without the user, their rows would be indistinguishable.
    val name = if (app.userId == 0) {
        baseName
    } else {
        stringResource(R.string.app_name_with_user, baseName, app.userId)
    }
    ListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { AppIcon(info, baseName, size = 48.dp) },
        supportingContent = {
            // Which of the user's folders it touched. Device information is
            // only summarised here for apps that touched no files; the rest
            // is on the app's own page.
            val files = filesSummary(app)
            if (files != null) {
                Text(
                    text = files,
                    // Emphasis is reserved for files that were actually read.
                    color = if (app.readUserFiles()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                deviceSummary(app)?.let {
                    Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        trailingContent = { Text(compactTime(app.lastSeen)) },
    ) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How many folder names or topics a row spells out before saying "and N more". */
private const val NAMED_IN_SUMMARY = 2

/** Which of the user's folders the app touched: "DCIM, Download and 3 more folders". */
@Composable
private fun filesSummary(app: AppSummary): String? {
    val separator = stringResource(R.string.summary_separator)
    val parts = buildList {
        if (app.folders.isNotEmpty()) {
            val named = app.folders.take(NAMED_IN_SUMMARY).joinToString(separator)
            val more = app.folderCount - minOf(app.folders.size, NAMED_IN_SUMMARY)
            add(
                if (more > 0) {
                    pluralStringResource(R.plurals.summary_folders_more, more, named, more)
                } else {
                    named
                },
            )
        } else {
            val files = app.count(Category.STORAGE)
            if (files > 0) add(pluralStringResource(R.plurals.summary_storage, files, files))
        }
        if (app.count(Category.PROVIDER) > 0) add(stringResource(R.string.summary_media_library))
    }
    if (parts.isEmpty()) return null
    val summary = parts.joinToString(separator)
    // Folders an app only looked into, or was refused, are marked as such.
    return if (app.readUserFiles()) summary else stringResource(R.string.summary_not_read, summary)
}

/** What device information the app read, in plain words: "Network hardware address, Battery status". */
@Composable
private fun deviceSummary(app: AppSummary): String? {
    val titles = app.topics.mapNotNull { topicTitle(it) }.map { stringResource(it) }
    if (titles.isEmpty()) return null
    val named = titles.take(NAMED_IN_SUMMARY).joinToString(stringResource(R.string.summary_separator))
    val more = titles.size - minOf(titles.size, NAMED_IN_SUMMARY)
    return if (more > 0) {
        pluralStringResource(R.plurals.summary_topics_more, more, named, more)
    } else {
        named
    }
}
