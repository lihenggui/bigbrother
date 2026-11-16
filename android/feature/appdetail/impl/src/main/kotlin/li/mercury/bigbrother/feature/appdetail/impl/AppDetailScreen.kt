package li.mercury.bigbrother.feature.appdetail.impl

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.OpenFileResult
import li.mercury.bigbrother.core.ui.R as UiR
import li.mercury.bigbrother.core.ui.SearchTopBar
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.openFile

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
        onSelectTab = viewModel::selectTab,
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

/**
 * A tab of the detail screen: files first, then one per category of private
 * data with records, then the app's info. [id] is what the navigation key and
 * the view model store; for the data tabs it is the category's name, so the
 * home screen's category filter opens the matching tab.
 */
internal sealed interface DetailTab {
    val id: String

    data object Files : DetailTab {
        override val id: String = DataCategory.FILES.name
    }

    data class Data(val category: DataCategory) : DetailTab {
        override val id: String get() = category.name
    }

    data object Info : DetailTab {
        override val id: String = "INFO"
    }
}

/** Files are kept when nothing at all was recorded, as the place for the empty state. */
private fun buildTabs(accesses: List<Access>, categories: Map<DataCategory, List<TopicGroup>>): List<DetailTab> =
    buildList {
        if (accesses.any { it.isFile() } || categories.isEmpty()) add(DetailTab.Files)
        categories.keys.forEach { add(DetailTab.Data(it)) }
        add(DetailTab.Info)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDetailScreen(
    state: AppDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectTab: (String) -> Unit,
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
    var location by rememberSaveable { mutableStateOf(Location.ROOT) }

    val accesses = state.accesses
    // The tabs come from every record, so that they stay put while a search
    // narrows what is in them.
    val allCategories = remember(accesses) { groupByCategory(accesses.orEmpty()) }
    val tabs = remember(accesses, allCategories) { accesses?.let { buildTabs(it, allCategories) } }
    val filtering = searching && query.isNotBlank()
    val shown = remember(accesses, query, filtering) {
        val needle = query.trim()
        accesses?.filter { !filtering || it.path.contains(needle, ignoreCase = true) }.orEmpty()
    }
    // Without a search, what is shown is everything, already grouped.
    val shownCategories = remember(shown, filtering, allCategories) {
        if (filtering) groupByCategory(shown) else allCategories
    }
    val fileCount = remember(accesses) { fileTabCount(accesses.orEmpty()) }

    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(enabled = searching, onBack = closeSearch)

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
                        Text(
                            text = stringResource(
                                R.string.header_subtitle,
                                app.packageName.ifEmpty { stringResource(R.string.uid_label, app.uid) },
                                stringResource(state.range.label),
                            ),
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
        // Only the top bar's space is reserved; lists scroll on behind the
        // navigation bar and pad their end instead.
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
            when {
                state.failure != null -> LoadFailureMessage(state.failure, onRetry)
                tabs == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())
                else -> TabbedContent(
                    tabs = tabs,
                    selected = state.selectedTab,
                    onSelect = onSelectTab,
                    tabCount = { tab ->
                        when (tab) {
                            DetailTab.Files -> fileCount
                            is DetailTab.Data -> allCategories[tab.category].orEmpty().size
                            DetailTab.Info -> null
                        }
                    },
                ) { tab, isCurrent ->
                    val listPadding = listContentPadding()
                    when (tab) {
                        DetailTab.Files -> FilesPage(
                            accesses = shown,
                            filtering = filtering,
                            truncated = state.truncated,
                            location = location,
                            // Back goes up a folder only on the visible tab,
                            // and only once search is closed.
                            handleBack = isCurrent && !searching,
                            onNavigate = { location = it },
                            onFileDetails = onFileDetails,
                        )
                        is DetailTab.Data -> CategoryPage(shownCategories[tab.category].orEmpty(), listPadding)
                        DetailTab.Info -> InfoPage(app, state.packageDetails, state.range, listPadding)
                    }
                }
            }
        }
    }
}

/**
 * A scrollable tab row over a pager, as in Blocker's app detail: tabs can be
 * tapped or swiped between, and each names how many items it holds.
 */
@Composable
private fun TabbedContent(
    tabs: List<DetailTab>,
    selected: String?,
    onSelect: (String) -> Unit,
    tabCount: (DetailTab) -> Int?,
    page: @Composable (tab: DetailTab, isCurrent: Boolean) -> Unit,
) {
    val initial = tabs.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    // A new set of tabs (records reloaded) starts a new pager on the tab
    // that was selected.
    val pagerState = key(tabs.map { it.id }) { rememberPagerState(initialPage = initial) { tabs.size } }
    // Only a page the user moves to is recorded. A requested tab that is
    // not there yet (records still loading) is kept, so a reload that adds
    // it opens on it.
    LaunchedEffect(pagerState, tabs) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect { onSelect(tabs[it].id) }
    }
    val scope = rememberCoroutineScope()
    PrimaryScrollableTabRow(
        selectedTabIndex = pagerState.currentPage,
        containerColor = Color.Transparent,
        edgePadding = 16.dp,
    ) {
        tabs.forEachIndexed { index, tab ->
            val title = stringResource(
                when (tab) {
                    DetailTab.Files -> R.string.tab_files
                    is DetailTab.Data -> tab.category.title
                    DetailTab.Info -> R.string.tab_info
                },
            )
            val count = tabCount(tab)
            Tab(
                selected = index == pagerState.currentPage,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = { Text(if (count != null) stringResource(R.string.tab_with_count, title, count) else title) },
            )
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { tabs[it].id },
        verticalAlignment = Alignment.Top,
    ) { index ->
        page(tabs[index], index == pagerState.currentPage)
    }
}

/** Padding for a tab's list: the usual margins, and room for the navigation bar. */
@Composable
internal fun listContentPadding(): PaddingValues {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp + bottom)
}

/** Animates a list row's appearance, movement and removal with the theme's motion. */
internal fun Modifier.animatedRow(scope: LazyItemScope, motion: MotionScheme): Modifier = with(scope) {
    animateItem(
        fadeInSpec = motion.defaultEffectsSpec(),
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.fastEffectsSpec(),
    )
}
