package li.mercury.bigbrother.feature.appdetail.impl

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.OpenFileResult
import li.mercury.bigbrother.core.ui.SearchTopBar
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.exactDateTime
import li.mercury.bigbrother.core.ui.exactTime
import li.mercury.bigbrother.core.ui.openFile
import li.mercury.bigbrother.core.ui.relativeTime
import li.mercury.bigbrother.core.ui.topicTitle
import li.mercury.bigbrother.feature.appdetail.impl.tree.FileNode
import li.mercury.bigbrother.feature.appdetail.impl.tree.buildFileTree
import li.mercury.bigbrother.core.ui.R as UiR
import kotlinx.coroutines.launch

/** The location shown within one app's records, navigated like a file browser. */
private object Location {
    /** The top level: storage volumes (or the single volume's contents) and the other groups. */
    const val ROOT = ""

    /** What the app reached through the media library. */
    const val MEDIA = "media:"

    // Any other value is the path of a folder on shared storage.
}

@Composable
fun AppDetailScreen(
    onBackClick: () -> Unit,
    viewModel: AppDetailViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AppDetailScreen(
        state = uiState,
        onBack = onBackClick,
        onRetry = viewModel::retry,
        onFileDetails = viewModel::showFileDetails,
        onDismissFileDetails = viewModel::dismissFileDetails,
        onOpenFile = { path ->
            scope.launch {
                val result = if (viewModel.ensureFileAccess()) {
                    openFile(context, path)
                } else {
                    OpenFileResult.NO_ACCESS
                }
                val message = when (result) {
                    OpenFileResult.OPENED -> null
                    OpenFileResult.MISSING -> R.string.open_file_missing
                    OpenFileResult.NO_VIEWER -> R.string.open_file_no_viewer
                    OpenFileResult.NO_ACCESS -> R.string.open_file_no_access
                }
                if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

/** What one app touched, as the screen presents it. */
private class DetailContent(
    /** One tree per storage volume. */
    val volumes: List<FileNode>,
    /** Collections reached through the media library and other providers. */
    val collections: List<Access>,
    /** Everything else the app went after, grouped by what it reveals. */
    val topics: List<TopicGroup>,
    /** Permissions the system recorded it using, grouped the same way. */
    val permissions: List<TopicGroup>,
) {
    /** The tabs that have something in them; files always do. */
    val tabs: List<DetailTab> = buildList {
        add(DetailTab.FILES)
        if (topics.isNotEmpty()) add(DetailTab.ACTIVITY)
        if (permissions.isNotEmpty()) add(DetailTab.PERMISSIONS)
    }

    /** Folder path -> the folder, and -> the path of the folder containing it. */
    private val nodes = HashMap<String, FileNode>()
    private val parents = HashMap<String, String>()

    init {
        fun index(node: FileNode, parent: String) {
            nodes[node.path] = node
            parents[node.path] = parent
            node.children.forEach { index(it, node.path) }
        }
        volumes.forEach { index(it, Location.ROOT) }
        // A single volume is not worth a level of its own: its contents are
        // shown at the top, so its children's parent is the top.
        volumes.singleOrNull()?.children?.forEach { parents[it.path] = Location.ROOT }
    }

    fun folder(path: String): FileNode? = nodes[path]

    /** Where "up" leads from [location]. */
    fun parentOf(location: String): String = parents[location] ?: Location.ROOT

    /** Folders and files shown at the top level. */
    val rootEntries: List<FileNode> get() = volumes.singleOrNull()?.children ?: volumes

    /** The folders from the top down to [path], for the breadcrumb. */
    fun trail(path: String): List<FileNode> {
        val trail = ArrayList<FileNode>()
        var current = path
        while (current != Location.ROOT) {
            trail.add(nodes[current] ?: break)
            current = parentOf(current)
        }
        return trail.asReversed()
    }
}

private class TopicGroup(val topic: String, val accesses: List<Access>)

/** A file counts as read when the system let the app open it. */
private fun Access.isSuccessfulOpen(): Boolean =
    allowed > 0 && (action == Action.READ || action == Action.WRITE || action == Action.READ_WRITE)

private fun arrange(accesses: List<Access>, readOnly: Boolean): DetailContent {
    val storage = accesses.filter {
        it.category == Category.STORAGE && (!readOnly || it.isSuccessfulOpen())
    }
    return DetailContent(
        volumes = buildVolumeTrees(storage),
        collections = accesses.filter { it.category == Category.PROVIDER },
        topics = accesses
            .filter { it.category !in WITH_OWN_PLACE }
            .byTopic(),
        permissions = accesses.filter { it.category == Category.PERMISSION }.byTopic(),
    )
}

/** Categories shown somewhere other than the activity tab. */
private val WITH_OWN_PLACE = setOf(Category.STORAGE, Category.PROVIDER, Category.PERMISSION)

/** Records made before a path had a topic are grouped under the path itself. */
private fun List<Access>.byTopic(): List<TopicGroup> =
    groupBy { it.topic ?: it.path }.map { (topic, group) -> TopicGroup(topic, group) }

/** A storage volume's root: `/storage/emulated/<user>` or `/storage/<volume id>`. */
private val VOLUME_ROOT = Regex("^/storage/(?:emulated/\\d+|[^/]+)")

/**
 * One tree per storage volume, so that each starts at a root the user knows
 * ("Internal storage") instead of at `/storage`. Paths are arranged relative
 * to their volume.
 */
private fun buildVolumeTrees(storage: List<Access>): List<FileNode> =
    storage
        .groupBy { VOLUME_ROOT.find(it.path)?.value.orEmpty() }
        .toList()
        // Internal storage first, by user number; then removable volumes.
        .sortedWith(
            compareBy(
                { (volume, _) -> PRIMARY_VOLUME.matchEntire(volume) == null },
                { (volume, _) -> PRIMARY_VOLUME.matchEntire(volume)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                { (volume, _) -> volume },
            ),
        )
        .map { (volume, accesses) ->
            val (atRoot, below) = accesses.partition { it.path == volume }
            // Trim the volume prefix so the tree starts below it, then put
            // the full paths back: they identify the nodes.
            val children = buildFileTree(below.map { it.copy(path = it.path.removePrefix(volume)) })
                .map { it.rebase(volume) }
            FileNode(
                name = volume,
                path = volume,
                accesses = atRoot,
                children = children,
                touchedCount = children.sumOf { it.touchedCount + if (it.accesses.isEmpty()) 0 else 1 },
            )
        }

/** Restore the volume prefix on a subtree built from volume-relative paths. */
private fun FileNode.rebase(volume: String): FileNode = copy(
    // Top-level names carry a leading slash from the tree builder.
    name = name.removePrefix("/"),
    path = volume + path,
    children = children.map { it.rebaseBelow(volume) },
)

private fun FileNode.rebaseBelow(volume: String): FileNode =
    copy(path = volume + path, children = children.map { it.rebaseBelow(volume) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDetailScreen(
    state: AppDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFileDetails: (path: String) -> Unit,
    onDismissFileDetails: () -> Unit,
    onOpenFile: (path: String) -> Unit,
) {
    state.fileDetails?.let { details ->
        FileDetailsSheet(details, onOpen = { onOpenFile(details.path) }, onDismiss = onDismissFileDetails)
    }
    val app = state.app
    val name = appDisplayName(app, state.appInfo)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // What the user came for is which files were read, so that is the default;
    // checks and blocked attempts are one tap away.
    var readOnly by rememberSaveable { mutableStateOf(true) }
    var location by rememberSaveable { mutableStateOf(Location.ROOT) }
    var tab by rememberSaveable { mutableStateOf(DetailTab.FILES) }

    val accesses = state.accesses
    val filtering = searching && query.isNotBlank()
    val content = remember(accesses, query, readOnly, filtering) {
        val needle = query.trim()
        accesses
            ?.filter { !filtering || it.path.contains(needle, ignoreCase = true) }
            ?.let { arrange(it, readOnly) }
    }
    // A folder can vanish when the filter changes; fall back to the top.
    val here = when {
        content == null -> Location.ROOT
        location == Location.MEDIA -> if (content.collections.isEmpty()) Location.ROOT else location
        content.folder(location) != null -> location
        else -> Location.ROOT
    }
    // A tab only exists while there is something to show in it.
    val shownTab = tab.takeIf { content != null && it in content.tabs } ?: DetailTab.FILES
    val showDevice = shownTab != DetailTab.FILES

    val closeSearch = {
        searching = false
        query = ""
    }
    // Back leaves search first, then goes up one folder, then leaves the screen.
    BackHandler(enabled = searching || (!showDevice && here != Location.ROOT)) {
        if (searching) closeSearch() else location = content?.parentOf(here) ?: Location.ROOT
    }

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
                    placeholder = stringResource(R.string.search_paths),
                    onClose = closeSearch,
                )
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    subtitle = {
                        Text(
                            text = app.packageName.ifEmpty { "uid ${app.uid}" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(UiR.string.back),
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
                        AppIcon(
                            state.appInfo,
                            name,
                            size = 40.dp,
                            modifier = Modifier.padding(end = 12.dp),
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.failure != null -> LoadFailureMessage(state.failure, onRetry)
                content == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())
                // What else the app went after, and which permissions it
                // used, are different questions from "which of my files", so
                // each gets its own tab, shown only when there is something
                // in it.
                showDevice -> {
                    DetailTabs(content.tabs, shownTab, onTab = { tab = it })
                    TopicList(
                        if (shownTab == DetailTab.PERMISSIONS) content.permissions else content.topics,
                    )
                }
                else -> {
                    if (content.tabs.size > 1) DetailTabs(content.tabs, shownTab, onTab = { tab = it })
                    ActivityFilter(readOnly, onReadOnly = { readOnly = it })
                    Breadcrumb(content, here, onNavigate = { location = it })
                    // Entering a folder slides the new list in from the end,
                    // leaving it slides back, like turning pages.
                    val motion = MaterialTheme.motionScheme
                    AnimatedContent(
                        targetState = here,
                        transitionSpec = {
                            val deeper = targetState.length >= initialState.length
                            val direction = if (deeper) 1 else -1
                            val slide = motion.defaultSpatialSpec<IntOffset>()
                            val fade = motion.fastEffectsSpec<Float>()
                            slideInHorizontally(slide) { it / 3 * direction } + fadeIn(fade) togetherWith
                                slideOutHorizontally(slide) { -it / 3 * direction } + fadeOut(fade)
                        },
                        label = "folder",
                    ) { shown ->
                        Listing(
                            content = content,
                            location = shown,
                            readOnly = readOnly,
                            filtering = filtering,
                            truncated = state.truncated,
                            onNavigate = { location = it },
                            onShowAll = { readOnly = false },
                            onFileDetails = onFileDetails,
                        )
                    }
                }
            }
        }
    }
}

private enum class DetailTab(val title: Int) {
    FILES(R.string.tab_files),

    /** Everything recorded by watching the app: device, network, services. */
    ACTIVITY(R.string.tab_activity),

    /** What the system's own permission records say. */
    PERMISSIONS(R.string.tab_permissions),
}

@Composable
private fun DetailTabs(tabs: List<DetailTab>, selected: DetailTab, onTab: (DetailTab) -> Unit) {
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
        containerColor = Color.Transparent,
    ) {
        for (tab in tabs) {
            Tab(
                selected = tab == selected,
                onClick = { onTab(tab) },
                text = { Text(stringResource(tab.title)) },
            )
        }
    }
}

/** What the app went after, or which permissions it used, one row per kind. */
@Composable
private fun TopicList(topics: List<TopicGroup>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(topics, key = { _, group -> group.topic }) { index, group ->
            TopicRow(group, index, topics.size)
        }
    }
}

/** Chooses between files that were read and everything the app did. */
@Composable
private fun ActivityFilter(readOnly: Boolean, onReadOnly: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = readOnly,
            onClick = { onReadOnly(true) },
            label = { Text(stringResource(R.string.filter_read)) },
        )
        FilterChip(
            selected = !readOnly,
            onClick = { onReadOnly(false) },
            label = { Text(stringResource(R.string.filter_all)) },
        )
    }
}

/** The path to the current folder; each part goes back to that folder. */
@Composable
private fun Breadcrumb(content: DetailContent, location: String, onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val single = content.volumes.singleOrNull()
        TextButton(onClick = { onNavigate(Location.ROOT) }) {
            Text(if (single != null) storageLabel(single) else stringResource(R.string.storage_root))
        }
        val trail: List<Pair<String, String>> = when (location) {
            Location.ROOT -> emptyList()
            Location.MEDIA -> listOf(location to stringResource(R.string.group_media))
            else -> content.trail(location).map { node ->
                node.path to if (node in content.volumes) storageLabel(node) else node.name
            }
        }
        for ((target, label) in trail) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onNavigate(target) }) { Text(label) }
        }
    }
}

/** The contents of one location: a folder, the media library group or the device group. */
@Composable
private fun Listing(
    content: DetailContent,
    location: String,
    readOnly: Boolean,
    filtering: Boolean,
    truncated: Boolean,
    onNavigate: (String) -> Unit,
    onShowAll: () -> Unit,
    onFileDetails: (path: String) -> Unit,
) {
    val entries = when (location) {
        Location.ROOT -> content.rootEntries
        Location.MEDIA -> emptyList()
        else -> content.folder(location)?.children.orEmpty()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        when (location) {
            Location.MEDIA -> itemsIndexed(
                content.collections,
                key = { _, access -> "collection:${access.path}:${access.action}" },
            ) { index, access ->
                CollectionRow(access, index, content.collections.size)
            }
            else -> {
                itemsIndexed(entries, key = { _, node -> node.path }) { index, node ->
                    EntryRow(
                        node = node,
                        isVolume = node in content.volumes,
                        readOnly = readOnly,
                        index = index,
                        count = entries.size,
                        // A folder is entered; a file shows when it was accessed.
                        onOpen = {
                            if (node.isFolder) onNavigate(node.path) else onFileDetails(node.path)
                        },
                    )
                }
                if (location == Location.ROOT) {
                    // What the app reached through the media library sits
                    // below the files, as a folder of its own.
                    val groups = buildList {
                        if (content.collections.isNotEmpty()) {
                            add(Group(Location.MEDIA, R.string.group_media, Icons.Outlined.PhotoLibrary, content.collections.size))
                        }
                    }
                    if (entries.isEmpty()) {
                        item(key = "no-files") {
                            NoFiles(readOnly, filtering, onShowAll, Modifier.fillParentMaxHeight(if (groups.isEmpty()) 0.7f else 0.35f))
                        }
                    }
                    itemsIndexed(groups, key = { _, group -> group.location }) { index, group ->
                        GroupRow(group, index, groups.size, onOpen = { onNavigate(group.location) })
                    }
                }
            }
        }
        if (truncated) {
            item(key = "truncated") {
                Text(
                    text = stringResource(R.string.results_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Shown where the file list would be when there is nothing to list. */
@Composable
private fun NoFiles(readOnly: Boolean, filtering: Boolean, onShowAll: () -> Unit, modifier: Modifier) {
    when {
        filtering -> Message(stringResource(UiR.string.no_matches), "", modifier)
        // Nothing was read, but there may be checks and blocked attempts.
        readOnly -> Message(
            title = stringResource(R.string.no_files_read),
            body = "",
            modifier = modifier,
            actionLabel = stringResource(R.string.filter_all),
            onAction = onShowAll,
        )
        else -> Message(stringResource(UiR.string.empty_title), "", modifier)
    }
}

/** A non-file group of records listed at the top level, opened like a folder. */
private class Group(val location: String, val title: Int, val icon: ImageVector, val size: Int)

@Composable
private fun LazyItemScope.GroupRow(group: Group, index: Int, count: Int, onOpen: () -> Unit) {
    ListItem(
        onClick = onOpen,
        modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { Icon(group.icon, contentDescription = null) },
        trailingContent = { Text(group.size.toString()) },
    ) {
        Text(stringResource(group.title))
    }
}

/** Animates a list row's appearance, movement and removal with the theme's motion. */
private fun Modifier.animatedRow(scope: LazyItemScope, motion: MotionScheme): Modifier = with(scope) {
    animateItem(
        fadeInSpec = motion.defaultEffectsSpec(),
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.fastEffectsSpec(),
    )
}

/** A folder, which opens, or a file, which shows what was done to it. */
@Composable
private fun LazyItemScope.EntryRow(
    node: FileNode,
    isVolume: Boolean,
    readOnly: Boolean,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val colors = ListItemDefaults.segmentedColors()
    val name = @Composable { Text(if (isVolume) storageLabel(node) else node.name) }
    val detail: (@Composable () -> Unit)? = node.accesses.takeIf { it.isNotEmpty() }?.let { accesses ->
        if (readOnly) {
            // Only reads are listed here; the trailing count gives their number.
            { Text(relativeTime(accesses.maxOf { it.lastSeen })) }
        } else {
            { AccessLines(accesses) }
        }
    }
    // How many times the file was read.
    val reads = node.accesses.filter { it.isSuccessfulOpen() }.sumOf { it.allowed }
    if (node.isFolder) {
        ListItem(
            onClick = onOpen,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            shapes = shapes,
            colors = colors,
            leadingContent = {
                Icon(
                    if (isVolume) Icons.Outlined.Storage else Icons.Outlined.Folder,
                    contentDescription = null,
                )
            },
            supportingContent = detail,
            trailingContent = { Text(node.touchedCount.toString()) },
            content = name,
        )
    } else {
        ListItem(
            onClick = onOpen,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            shapes = shapes,
            colors = colors,
            leadingContent = {
                Icon(
                    // A path that was listed is a folder, even with nothing recorded inside.
                    if (node.accesses.any { it.action == Action.LIST }) {
                        Icons.Outlined.Folder
                    } else {
                        Icons.AutoMirrored.Outlined.InsertDriveFile
                    },
                    contentDescription = null,
                )
            },
            supportingContent = detail,
            trailingContent = if (reads > 0) {
                { Text(stringResource(R.string.read_count, reads)) }
            } else {
                null
            },
            content = name,
        )
    }
}

/** A volume root in words: "Internal storage", or the volume's id for removable storage. */
@Composable
private fun storageLabel(node: FileNode): String {
    val user = PRIMARY_VOLUME.matchEntire(node.path)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        user == 0 -> stringResource(R.string.internal_storage)
        user != null -> stringResource(R.string.internal_storage_user, user)
        node.path.startsWith("/storage/") ->
            stringResource(R.string.removable_storage, node.path.removePrefix("/storage/"))
        else -> node.name
    }
}

private val PRIMARY_VOLUME = Regex("/storage/emulated/(\\d+)")

/**
 * What the app did to a path, on as few lines as possible: the actions with
 * their counts ("Read 3×; Checked 12×, blocked"), then when it last happened.
 */
@Composable
private fun AccessLines(accesses: List<Access>) {
    val actions = accesses.map { access ->
        val label = actionLabel(access)
        when {
            access.action == Action.QUERY -> pluralStringResource(
                R.plurals.access_query,
                access.allowed,
                access.allowed,
                access.items,
            )
            // An access the system refused every time is a failed attempt.
            access.allowed == 0 -> stringResource(R.string.access_blocked, label, access.denied)
            access.denied > 0 -> stringResource(
                R.string.access_partly_blocked,
                label,
                access.allowed + access.denied,
                access.denied,
            )
            else -> stringResource(R.string.access_allowed, label, access.allowed)
        }
    }
    val lastSeen = accesses.maxOf { it.lastSeen }
    val time = remember(lastSeen) { relativeTime(lastSeen) }
    val allBlocked = accesses.all { it.allowed == 0 }
    Column {
        Text(
            text = actions.joinToString(stringResource(R.string.access_separator)),
            color = if (allBlocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(text = time, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun actionLabel(access: Access): String = actionLabel(access.action, access.viaProvider)

@Composable
private fun actionLabel(action: Action, viaProvider: Boolean): String = stringResource(
    when (action) {
        Action.READ -> if (viaProvider) R.string.action_read_provider else R.string.action_read
        Action.WRITE -> if (viaProvider) R.string.action_write_provider else R.string.action_write
        Action.READ_WRITE ->
            if (viaProvider) R.string.action_write_provider else R.string.action_read_write
        Action.LIST -> R.string.action_list
        Action.PROBE -> R.string.action_probe
        Action.WATCH -> R.string.action_watch
        Action.QUERY -> R.string.action_query
        Action.CONNECT -> R.string.action_connect
        Action.EXEC -> R.string.action_exec
        Action.CALL -> R.string.action_call
    },
)

/** When one file was accessed: a line per access, most recent first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileDetailsSheet(details: FileDetails, onOpen: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = details.path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleLarge,
            )
            SelectionContainer {
                Text(
                    text = details.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val periods = details.periods
            when {
                details.failure != null -> Text(
                    text = (details.failure as? LoadFailure.Other)?.message
                        ?: stringResource(UiR.string.error_title),
                    color = MaterialTheme.colorScheme.error,
                )
                periods == null -> BigBrotherLoadingIndicator(Modifier.padding(vertical = 24.dp))
                else -> LazyColumn(
                    // Leaves room for the button below on a long history.
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(periods) { period -> PeriodRow(period) }
                }
            }
            // A folder that was only listed has nothing to show in a viewer.
            val viewable = periods?.none { it.action == Action.LIST } ?: true
            if (viewable) {
                FilledTonalButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_file))
                }
            }
        }
    }
}

@Composable
private fun PeriodRow(period: AccessPeriod) {
    val label = actionLabel(period.action, period.viaProvider)
    val blocked = period.allowed == 0
    Column {
        Text(
            // A single time, or the span of an hour recorded only in aggregate.
            text = if (period.firstSeen == period.lastSeen) {
                exactDateTime(period.firstSeen)
            } else {
                stringResource(
                    R.string.time_span,
                    exactDateTime(period.firstSeen),
                    exactTime(period.lastSeen),
                )
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = when {
                blocked -> stringResource(R.string.access_blocked, label, period.denied)
                period.denied > 0 -> stringResource(
                    R.string.access_partly_blocked,
                    label,
                    period.allowed + period.denied,
                    period.denied,
                )
                else -> stringResource(R.string.access_allowed, label, period.allowed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (blocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** A media library collection (photos, videos...) the app listed or opened items of. */
@Composable
private fun LazyItemScope.CollectionRow(access: Access, index: Int, count: Int) {
    val label = collectionLabel(access.path)
    ListItem(
        modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        supportingContent = { AccessLines(listOf(access)) },
    ) {
        if (label != null) {
            Text(stringResource(label))
        } else {
            Text(access.path, fontFamily = FontFamily.Monospace)
        }
    }
}

/** A readable name for a content URI's collection, if it is a well-known one. */
private fun collectionLabel(uri: String): Int? {
    val authority = uri.substringAfter("://").substringBefore('/')
    val path = uri.substringAfter("://").substringAfter('/', "")
    return when {
        authority == "media" && "/images" in "/$path" -> R.string.collection_images
        authority == "media" && "/video" in "/$path" -> R.string.collection_videos
        authority == "media" && "/audio" in "/$path" -> R.string.collection_audio
        authority == "media" && "/downloads" in "/$path" -> R.string.collection_downloads
        authority == "media" -> R.string.collection_files
        "externalstorage" in authority -> R.string.collection_documents
        "downloads" in authority -> R.string.collection_downloads
        else -> null
    }
}

/**
 * One kind of device or system information: what it is and how often it was
 * read. Tapping reveals the exact paths.
 */
@Composable
private fun LazyItemScope.TopicRow(group: TopicGroup, index: Int, count: Int) {
    var expanded by rememberSaveable(group.topic) { mutableStateOf(false) }
    val title = topicTitle(group.topic)
    val motion = MaterialTheme.motionScheme
    ListItem(
        onClick = { expanded = !expanded },
        modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        supportingContent = {
            Column {
                val allowed = group.accesses.sumOf { it.allowed }
                val denied = group.accesses.sumOf { it.denied }
                val lastSeen = group.accesses.maxOf { it.lastSeen }
                // What is known differs by how it was observed: a file was
                // read, a service was only seen being asked.
                val kind = group.accesses.map { it.action }.distinct().singleOrNull()
                Text(
                    text = if (allowed == 0) {
                        val blocked = if (kind == Action.EXEC) R.plurals.total_not_run else R.plurals.total_blocked
                        pluralStringResource(blocked, denied, denied)
                    } else {
                        val done = when (kind) {
                            Action.CALL -> R.plurals.total_requested
                            Action.CONNECT -> R.plurals.total_contacted
                            Action.EXEC -> R.plurals.total_ran
                            else -> R.plurals.total_allowed
                        }
                        pluralStringResource(done, allowed, allowed)
                    } + stringResource(R.string.access_separator) +
                        remember(lastSeen) { relativeTime(lastSeen) },
                    color = if (allowed == 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(motion.defaultSpatialSpec()) +
                        fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) +
                        fadeOut(motion.fastEffectsSpec()),
                ) {
                    Column(Modifier.padding(top = 8.dp)) {
                        for (path in group.accesses.map { it.path }.distinct()) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
    ) {
        Text(if (title != null) stringResource(title) else group.topic)
    }
}
