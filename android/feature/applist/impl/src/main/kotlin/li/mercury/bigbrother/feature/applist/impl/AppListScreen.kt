package li.mercury.bigbrother.feature.applist.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
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
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.SearchTopBar
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.compactTime
import li.mercury.bigbrother.core.ui.dataCategories
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.topicCategory
import li.mercury.bigbrother.core.ui.topicTitle
import li.mercury.bigbrother.core.ui.R as UiR

/** How the app list is ordered. */
private enum class SortOrder(val label: Int) {
    /** By the most sensitive category accessed, then most recent first. */
    SENSITIVITY(R.string.sort_sensitivity),
    RECENT(R.string.sort_recent),
    NAME(R.string.sort_name),
}

@Composable
fun AppListScreen(
    navigateToAppDetail: (uid: Int, packageName: String, range: TimeRange, tab: String?) -> Unit,
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
        // With a category selected, the app opens on that category's tab.
        onOpen = { app, category -> navigateToAppDetail(app.uid, app.packageName, uiState.range, category?.name) },
        onInstallModule = viewModel::installModule,
        onStartDaemon = viewModel::startDaemon,
        onShowSystemApps = viewModel::setShowSystemApps,
    )
}

/** An app with the categories of private data it accessed, computed once per load. */
private class Entry(val app: AppSummary, val categories: List<DataCategory>) {
    /**
     * Position in the sensitivity order: apps that read the user's files
     * lead; the rest follow their most sensitive other category. Merely
     * looking for files, which nearly every app does, does not count.
     */
    val rank: Int = when {
        app.filesRead > 0 || app.count(Category.PROVIDER) > 0 -> DataCategory.FILES.ordinal
        else -> categories.firstOrNull { it != DataCategory.FILES }?.ordinal ?: DataCategory.FILES.ordinal
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppListScreen(
    state: AppListUiState,
    onRange: (TimeRange) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (AppSummary, DataCategory?) -> Unit,
    onInstallModule: () -> Unit,
    onStartDaemon: () -> Unit,
    onShowSystemApps: (Boolean) -> Unit,
) {
    // This app's own probes are not what the user is looking for.
    val ownPackage = LocalContext.current.packageName
    // System apps, and isolated processes not traced to an app, are hidden
    // unless asked for. An uninstalled app, whose details are unknown, is not
    // assumed to be a system app.
    val entries = remember(state.apps, state.appInfo, state.showSystemApps, ownPackage) {
        state.apps
            ?.filter { it.packageName != ownPackage }
            ?.filter { state.showSystemApps || !isHiddenByDefault(it, state.appInfo) }
            ?.map { Entry(it, it.dataCategories()) }
    }
    val hiddenSystemApps = state.apps.orEmpty().count { it.packageName != ownPackage } -
        entries.orEmpty().size
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortOrder.SENSITIVITY) }
    var category by rememberSaveable { mutableStateOf<DataCategory?>(null) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    if (showSortSheet) {
        SortSheet(
            sort = sort,
            onSort = { sort = it },
            showSystemApps = state.showSystemApps,
            onShowSystemApps = onShowSystemApps,
            onDismiss = { showSortSheet = false },
        )
    }
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
                        { StatusLine(recording, state.unavailable, entries?.size ?: 0) }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(UiR.string.search),
                            )
                        }
                        IconButton(onClick = { showSortSheet = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = stringResource(R.string.sort),
                            )
                        }
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
        // Only the top bar's space is reserved; the list scrolls on behind
        // the navigation bar and pads its end instead.
        val layoutDirection = LocalLayoutDirection.current
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                ),
        ) {
            if (state.failure == null && entries != null) {
                FilterBar(
                    range = state.range,
                    onRange = onRange,
                    entries = entries,
                    selected = category,
                    onSelect = { category = it },
                )
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing && state.apps != null,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.failure == LoadFailure.ModuleMissing ->
                        ModuleSetupMessage(state.moduleSetup, onInstallModule, onRefresh)
                    state.failure != null -> LoadFailureMessage(state.failure, onRetry = onRefresh)
                    entries == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())
                    else -> AppList(
                        entries = entries,
                        appInfo = state.appInfo,
                        query = query,
                        sort = sort,
                        category = category,
                        stopped = state.recording == false,
                        starting = state.daemonStarting,
                        startError = state.daemonStartError,
                        moduleOutdated = state.moduleOutdated,
                        modulePendingReboot = state.modulePendingReboot,
                        hiddenSystemApps = hiddenSystemApps,
                        showSystemApps = state.showSystemApps,
                        onOpen = onOpen,
                        onInstallModule = onInstallModule,
                        onStartDaemon = onStartDaemon,
                        onShowSystemApps = { onShowSystemApps(true) },
                    )
                }
            }
        }
    }
}

/** Under the title: how many apps have records, or that nothing is being recorded. */
@Composable
private fun StatusLine(recording: Boolean, unavailable: List<String>, apps: Int) {
    if (!recording) {
        Text(stringResource(R.string.status_stopped), color = MaterialTheme.colorScheme.error)
        return
    }
    Column {
        Text(
            if (apps == 0) {
                stringResource(R.string.status_none)
            } else {
                pluralStringResource(R.plurals.status_summary, apps, apps)
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

/**
 * The time range, then one chip per category of private data with the
 * number of apps that accessed it. Selecting a category narrows the list to
 * those apps; selecting it again clears the filter.
 */
@Composable
private fun FilterBar(
    range: TimeRange,
    onRange: (TimeRange) -> Unit,
    entries: List<Entry>,
    selected: DataCategory?,
    onSelect: (DataCategory?) -> Unit,
) {
    // The selected chip stays even when nothing is left in it, so that the
    // filter can always be cleared where it was set.
    val counts = remember(entries, selected) {
        DataCategory.entries
            .map { category -> category to entries.count { category in it.categories } }
            .filter { (category, count) -> count > 0 || category == selected }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "range") { RangeChip(range, onRange) }
        items(counts, key = { (category, _) -> category.name }) { (category, count) ->
            val isSelected = category == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else category) },
                label = { Text(stringResource(R.string.category_chip, stringResource(category.title), count)) },
                leadingIcon = {
                    Icon(
                        category.icon,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

/** A chip naming the time range; opens a menu to change it. */
@Composable
private fun RangeChip(range: TimeRange, onRange: (TimeRange) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            label = { Text(stringResource(range.label)) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.menu_time_range),
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in TimeRange.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = { RadioButton(selected = option == range, onClick = null) },
                    onClick = {
                        onRange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Sort order and the system apps switch, in a bottom sheet as in Blocker's app list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    showSystemApps: Boolean,
    onShowSystemApps: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Rows take the sheet's colour instead of drawing their own surface.
    val sheetItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            SheetHeading(R.string.sort)
            Column(Modifier.selectableGroup()) {
                for (order in SortOrder.entries) {
                    ListItem(
                        headlineContent = { Text(stringResource(order.label)) },
                        leadingContent = { RadioButton(selected = order == sort, onClick = null) },
                        colors = sheetItemColors,
                        modifier = Modifier.selectable(
                            selected = order == sort,
                            role = Role.RadioButton,
                            onClick = { onSort(order) },
                        ),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.show_system_apps)) },
                supportingContent = { Text(stringResource(R.string.show_system_apps_summary)) },
                trailingContent = { Switch(checked = showSystemApps, onCheckedChange = null) },
                colors = sheetItemColors,
                modifier = Modifier.toggleable(
                    value = showSystemApps,
                    role = Role.Switch,
                    onValueChange = onShowSystemApps,
                ),
            )
        }
    }
}

/** Names a group of options inside the sheet. */
@Composable
private fun SheetHeading(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
    entries: List<Entry>,
    appInfo: Map<String, AppInfo>,
    query: String,
    sort: SortOrder,
    category: DataCategory?,
    stopped: Boolean,
    starting: Boolean,
    startError: StartFailure?,
    moduleOutdated: Boolean,
    modulePendingReboot: Boolean,
    hiddenSystemApps: Int,
    showSystemApps: Boolean,
    onOpen: (AppSummary, DataCategory?) -> Unit,
    onInstallModule: () -> Unit,
    onStartDaemon: () -> Unit,
    onShowSystemApps: () -> Unit,
) {
    val resources = LocalResources.current
    val nameOf = { app: AppSummary -> appDisplayName(app, appInfo[app.packageName], resources) }
    val shown = remember(entries, appInfo, query, sort, category) {
        val needle = query.trim()
        entries
            .filter { category == null || category in it.categories }
            .filter {
                needle.isEmpty() ||
                    nameOf(it.app).contains(needle, ignoreCase = true) ||
                    it.app.packageName.contains(needle, ignoreCase = true)
            }
            .let { matching ->
                when (sort) {
                    SortOrder.SENSITIVITY -> matching.sortedWith(
                        compareBy<Entry> { it.rank }.thenByDescending { it.app.lastSeen },
                    )
                    // The daemon already lists the most recent first.
                    SortOrder.RECENT -> matching
                    SortOrder.NAME -> matching.sortedBy { nameOf(it.app).lowercase() }
                }
            }
    }

    // A different filter or order is a different list: start it at the top
    // instead of keeping whichever row happened to be first.
    val listState = rememberSaveable(sort, category, showSystemApps, saver = LazyListState.Saver) {
        LazyListState()
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        // A staged update comes first: starting now would run the old module.
        when {
            modulePendingReboot -> item(key = "restart") {
                ModuleNotice(stringResource(R.string.module_pending_reboot))
            }
            stopped -> item(key = "stopped") {
                ModuleNotice(
                    text = if (starting) {
                        stringResource(R.string.daemon_starting)
                    } else when (startError) {
                        null -> stringResource(R.string.daemon_stopped)
                        StartFailure.ModuleDisabled -> stringResource(R.string.module_disabled)
                        StartFailure.NotReporting -> stringResource(R.string.daemon_not_reporting)
                        is StartFailure.Failed ->
                            stringResource(R.string.daemon_start_failed, startError.output)
                    },
                    // A disabled module is turned back on in the root manager, not here.
                    actionLabel = stringResource(R.string.start_daemon)
                        .takeUnless { starting || startError == StartFailure.ModuleDisabled },
                    onAction = onStartDaemon,
                )
            }
            moduleOutdated -> item(key = "update") {
                ModuleNotice(
                    text = stringResource(R.string.module_outdated),
                    actionLabel = stringResource(R.string.update_module),
                    onAction = onInstallModule,
                )
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                val modifier = Modifier.fillParentMaxHeight(0.7f)
                when {
                    query.isNotBlank() -> Message(stringResource(UiR.string.no_matches), "", modifier)
                    // There are records, but only for apps that are hidden.
                    hiddenSystemApps > 0 && category == null -> Message(
                        title = stringResource(R.string.only_system_apps),
                        body = "",
                        modifier = modifier,
                        actionLabel = stringResource(R.string.show_system_apps),
                        onAction = onShowSystemApps,
                    )
                    else -> Message(stringResource(UiR.string.empty_title), "", modifier)
                }
            }
        }
        itemsIndexed(shown, key = { _, entry -> entry.app.uid }) { index, entry ->
            AppRow(
                entry = entry,
                info = appInfo[entry.app.packageName],
                category = category,
                index = index,
                count = shown.size,
                onClick = { onOpen(entry.app, category) },
            )
        }
    }
}

/**
 * Whether an app is left out unless system apps are shown: system apps,
 * whose reading data is how they work, and isolated processes that could not
 * be traced to an app, which are mostly system sandboxes too.
 */
private fun isHiddenByDefault(app: AppSummary, appInfo: Map<String, AppInfo>): Boolean =
    appInfo[app.packageName]?.isSystem == true || app.isUnattributedIsolated

/** A notice at the top of the list, with an optional action. */
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
    entry: Entry,
    info: AppInfo?,
    category: DataCategory?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val app = entry.app
    val baseName = appDisplayName(app, info)
    // The same app can run as several Android users (clones, work profile);
    // without the user, their rows would be indistinguishable.
    // An unattributed isolated process is named by its uid, which already
    // says which user it runs as.
    val name = if (app.userId == 0 || app.isUnattributedIsolated) {
        baseName
    } else {
        stringResource(R.string.app_name_with_user, baseName, app.userId)
    }
    // With a category selected, the row says what within it was accessed;
    // otherwise, which categories.
    val summary = if (category != null) {
        categoryDetail(app, category)
    } else {
        namedList(entry.categories.map { stringResource(it.title) })
    }
    ListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { AppIcon(info, baseName, size = 48.dp) },
        supportingContent = summary?.let {
            { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        trailingContent = { Text(compactTime(app.lastSeen)) },
    ) {
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How many names a row spells out before counting the rest. */
private const val NAMED_IN_SUMMARY = 2

/** "A, B and 3 more", or null for an empty list. */
@Composable
private fun namedList(names: List<String>, total: Int = names.size): String? {
    if (names.isEmpty()) return null
    val named = names.take(NAMED_IN_SUMMARY).joinToString(stringResource(R.string.summary_separator))
    val more = total - minOf(names.size, NAMED_IN_SUMMARY)
    return if (more > 0) pluralStringResource(R.plurals.summary_more, more, named, more) else named
}

/** What the app accessed within [category]: folders for files, item names for the rest. */
@Composable
private fun categoryDetail(app: AppSummary, category: DataCategory): String? {
    if (category == DataCategory.FILES) {
        val parts = buildList {
            if (app.folders.isNotEmpty()) {
                namedList(app.folders, total = app.folderCount)?.let(::add)
            } else if (app.count(Category.STORAGE) > 0) {
                add(pluralStringResource(R.plurals.summary_storage, app.count(Category.STORAGE), app.count(Category.STORAGE)))
            }
            if (app.count(Category.PROVIDER) > 0) add(stringResource(R.string.summary_media_library))
        }
        val summary = parts.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.summary_separator))
            ?: return null
        // Folders an app only listed, or was refused, are marked as such.
        val read = app.filesRead > 0 || app.count(Category.PROVIDER) > 0
        return if (read) summary else stringResource(R.string.summary_not_read, summary)
    }
    val titles = app.topics
        .filter { (topicCategory(it) ?: DataCategory.DEVICE_PROFILE) == category }
        .map { topic -> topicTitle(topic)?.let { stringResource(it) } ?: topic }
    return namedList(titles)
}
