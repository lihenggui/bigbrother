package li.mercury.bigbrother.feature.overview.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.AppListItem
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.R as UiR
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.categoryDetail
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.readFiles
import li.mercury.bigbrother.core.ui.shortTime

/** The apps that accessed one kind of data, and what each accessed within it. */
@Composable
fun CategoryScreen(
    selectedUid: Int?,
    onBack: () -> Unit,
    onOpenApp: (AppSummary, TimeRange) -> Unit,
    viewModel: CategoryViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    CategoryScreen(uiState, selectedUid, onBack, viewModel::refresh, onOpenApp)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CategoryScreen(
    state: CategoryUiState,
    selectedUid: Int?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenApp: (AppSummary, TimeRange) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(state.category.title)) },
                subtitle = { Text(stringResource(state.range.label)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.back))
                    }
                },
                actions = { ShapedIcon(state.category.icon, Modifier.padding(end = 12.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val apps = state.apps
        when {
            state.failure != null -> LoadFailureMessage(state.failure, onRetry, Modifier.padding(padding))
            apps == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize().padding(padding))
            else -> AppsByCategory(state, apps, selectedUid, padding, onOpenApp)
        }
    }
}

@Composable
private fun AppsByCategory(
    state: CategoryUiState,
    apps: List<AppSummary>,
    selectedUid: Int?,
    padding: PaddingValues,
    onOpenApp: (AppSummary, TimeRange) -> Unit,
) {
    // For files, the apps that read some are told apart from those that only looked.
    val groups = if (state.category == DataCategory.FILES) {
        val (read, looked) = apps.partition { it.readFiles }
        listOf(R.plurals.group_read_files to read, R.plurals.group_looked_only to looked)
    } else {
        listOf(null to apps)
    }.filter { (_, members) -> members.isNotEmpty() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding(),
            bottom = 24.dp + bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (apps.isEmpty()) {
            item(key = "empty") {
                Message(stringResource(UiR.string.empty_title), "", Modifier.fillParentMaxHeight(0.7f))
            }
        }
        for ((title, members) in groups) {
            if (title != null) {
                item(key = "header:$title") {
                    SectionHeader(pluralStringResource(title, members.size, members.size))
                }
            }
            itemsIndexed(members, key = { _, app -> app.uid }) { index, app ->
                AppListItem(
                    app = app,
                    info = state.appInfo[app.packageName],
                    supporting = categoryDetail(app, state.category),
                    trailing = shortTime(app.lastSeen),
                    index = index,
                    count = members.size,
                    selected = app.uid == selectedUid,
                    onClick = { onOpenApp(app, state.range) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
