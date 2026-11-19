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

package li.mercury.bigbrother.feature.overview.impl

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.LoadFailure
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.InlineMessage
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.TimeRangeButtonGroup
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.namedList
import li.mercury.bigbrother.core.ui.topicTitle
import li.mercury.bigbrother.core.ui.R as UiR

@Composable
fun OverviewScreen(
    topBar: @Composable () -> Unit,
    onOpenCategory: (DataCategory) -> Unit,
    viewModel: OverviewViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The app counts as started once the overview shows records, or why it
    // can't (`am start -W`, startup benchmarks).
    ReportDrawnWhen { uiState.files != null || uiState.failure != null }
    // Records keep accumulating in the background, so reload on return.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshIfStale()
        onPauseOrDispose {}
    }
    OverviewScreen(
        state = uiState,
        topBar = topBar,
        onRange = viewModel::setRange,
        onRefresh = viewModel::refresh,
        onOpenCategory = onOpenCategory,
        onInstallModule = viewModel::installModule,
        onUpdateModule = viewModel::updateModule,
        onStartDaemon = viewModel::startDaemon,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverviewScreen(
    state: OverviewUiState,
    topBar: @Composable () -> Unit,
    onRange: (TimeRange) -> Unit,
    onRefresh: () -> Unit,
    onOpenCategory: (DataCategory) -> Unit,
    onInstallModule: () -> Unit,
    onUpdateModule: () -> Unit,
    onStartDaemon: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = topBar,
    ) { padding ->
        val files = state.files
        PullToRefreshBox(
            isRefreshing = state.refreshing && files != null,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            when {
                state.failure == LoadFailure.ModuleMissing ->
                    ModuleSetupMessage(state.moduleSetup, onInstallModule, onRefresh)

                state.failure != null -> LoadFailureMessage(state.failure, onRetry = onRefresh)

                files == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())

                else -> Content(state, files, onRange, onOpenCategory, onUpdateModule, onStartDaemon)
            }
        }
    }
}

@Composable
private fun Content(
    state: OverviewUiState,
    files: FilesOverview,
    onRange: (TimeRange) -> Unit,
    onOpenCategory: (DataCategory) -> Unit,
    onUpdateModule: () -> Unit,
    onStartDaemon: () -> Unit,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wider windows use the room for columns rather than longer lines.
        val columns = when {
            maxWidth >= 1200.dp -> 3
            maxWidth >= 600.dp -> 2
            else -> 1
        }
        val margin = if (columns == 1) 16.dp else 24.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = margin, end = margin, bottom = 24.dp + bottomInset),
        ) {
            StatusCards(state, onUpdateModule, onStartDaemon)
            TimeRangeButtonGroup(
                range = state.range,
                onRange = onRange,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            FilesCard(files, state.appInfo, wide = columns > 1, onClick = { onOpenCategory(DataCategory.FILES) })
            if (state.categories.isEmpty()) {
                if (files.readers.isEmpty() && files.lookedOnly == 0) {
                    // The column scrolls, so the message must not: see InlineMessage.
                    InlineMessage(stringResource(UiR.string.empty_title), "", Modifier.padding(top = 24.dp))
                }
            } else {
                SectionHeader(
                    title = stringResource(R.string.other_categories),
                    trailing = pluralStringResource(
                        R.plurals.category_count,
                        state.categories.size,
                        state.categories.size,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                )
                CategoryColumns(state.categories, columns, onOpenCategory)
            }
        }
    }
}

/** The categories in [columns] segmented groups side by side, filled column by column. */
@Composable
private fun CategoryColumns(categories: List<CategoryOverview>, columns: Int, onOpen: (DataCategory) -> Unit) {
    val perColumn = (categories.size + columns - 1) / columns
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        categories.chunked(perColumn).forEach { chunk ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                chunk.forEachIndexed { index, category ->
                    CategoryRow(category, index, chunk.size) { onOpen(category.category) }
                }
            }
        }
        // Keeps the last column as wide as the others when there are fewer chunks.
        repeat(columns - (categories.size + perColumn - 1) / perColumn) { Spacer(Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryRow(category: CategoryOverview, index: Int, count: Int, onClick: () -> Unit) {
    // A topic named like its category ("Contacts") would only repeat the title.
    val title = stringResource(category.category.title)
    val topics = namedList(
        category.topics.map { topic -> topicTitle(topic)?.let { stringResource(it) } ?: topic }.filter { it != title },
    )
    val apps = pluralStringResource(UiR.plurals.app_count, category.apps, category.apps)
    ListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { ShapedIcon(category.category.icon) },
        supportingContent = {
            Text(
                text = listOfNotNull(topics, apps).joinToString(stringResource(UiR.string.dot_separator)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) {
        Text(title)
    }
}

/** Apps shown on the files card before the rest are counted. */
private const val ICONS_SHOWN = 5

/**
 * The overview's one emphasized element: how many apps read the user's
 * files, which of them, and from where. Opens the list of those apps.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilesCard(files: FilesOverview, appInfo: Map<String, AppInfo>, wide: Boolean, onClick: () -> Unit) {
    val anyRead = files.readers.isNotEmpty()
    // Without reads there is nothing to draw attention to.
    val container = if (anyRead) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (anyRead) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapedIcon(
                    icon = Icons.Outlined.FolderOpen,
                    containerColor = if (anyRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (anyRead) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(DataCategory.FILES.title),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
            if (anyRead) {
                val count = files.readers.size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        Text(count.toString(), style = MaterialTheme.typography.displayMediumEmphasized)
                        Text(
                            text = pluralStringResource(R.plurals.readers_label, count),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                        )
                    }
                    if (wide) IconStack(files, appInfo)
                }
                if (!wide) IconStack(files, appInfo)
                val places = buildList {
                    if (files.folders.isNotEmpty()) add(files.folders.joinToString(stringResource(UiR.string.summary_separator)))
                    if (files.mediaLibrary) add(stringResource(UiR.string.summary_media_library))
                }
                if (places.isNotEmpty()) {
                    Text(places.joinToString(stringResource(UiR.string.dot_separator)), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(stringResource(R.string.no_reads), style = MaterialTheme.typography.headlineSmall)
            }
            if (files.lookedOnly > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ManageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = pluralStringResource(R.plurals.looked_only, files.lookedOnly, files.lookedOnly),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** The icons of the first apps that read files, overlapping, then how many more. */
@Composable
private fun IconStack(files: FilesOverview, appInfo: Map<String, AppInfo>) {
    val ring = MaterialTheme.colorScheme.primaryContainer
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Overlapping(overlap = 8.dp) {
            for (app in files.readers.take(ICONS_SHOWN)) {
                val info = appInfo[app.packageName]
                AppIcon(
                    info = info,
                    name = appDisplayName(app, info),
                    size = 32.dp,
                    modifier = Modifier.ring(ring, 2.dp),
                )
            }
        }
        val more = files.readers.size - ICONS_SHOWN
        if (more > 0) {
            Text(stringResource(R.string.more_count, more), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Lays its children out in a row, each overlapping the previous one by [overlap], the first on top. */
@Composable
private fun Overlapping(overlap: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val step = overlap.roundToPx()
        val width = (placeables.sumOf { it.width } - step * (placeables.size - 1)).coerceAtLeast(0)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2, zIndex = (placeables.size - index).toFloat())
                x += placeable.width - step
            }
        }
    }
}

/** A ring in [color] around a round icon, setting it off the icon it overlaps. */
private fun Modifier.ring(color: Color, width: Dp): Modifier = this
    .clip(CircleShape)
    .border(width, color, CircleShape)

/** Status that needs the user, shown only when something is wrong. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCards(state: OverviewUiState, onUpdateModule: () -> Unit, onStartDaemon: () -> Unit) {
    // A staged update comes first: starting now would run the old module.
    when {
        state.modulePendingReboot -> StatusCard(
            icon = Icons.Outlined.SystemUpdate,
            title = stringResource(R.string.module_pending_reboot),
            body = null,
            error = false,
        )

        state.recording == false -> {
            val error = state.daemonStartError
            StatusCard(
                icon = Icons.Filled.ErrorOutline,
                title = stringResource(R.string.status_stopped),
                body = when {
                    state.daemonStarting -> stringResource(R.string.daemon_starting)
                    error == null -> stringResource(R.string.daemon_stopped)
                    error == StartFailure.ModuleDisabled -> stringResource(R.string.module_disabled)
                    error == StartFailure.NotReporting -> stringResource(R.string.daemon_not_reporting)
                    error is StartFailure.Failed -> stringResource(R.string.daemon_start_failed, error.output)
                    else -> null
                },
                error = true,
                // A disabled module is turned back on in the root manager, not here.
                action = stringResource(R.string.start_daemon)
                    .takeUnless { state.daemonStarting || error == StartFailure.ModuleDisabled },
                busy = state.daemonStarting,
                onAction = onStartDaemon,
            )
        }

        state.moduleUpdate != ModuleUpdate.Idle -> ModuleUpdateCard(state.moduleUpdate, onUpdateModule, onStartDaemon)
    }
}

/** Bringing the module up to date with the app: while it happens, and when it did not work. */
@Composable
private fun ModuleUpdateCard(update: ModuleUpdate, onUpdateModule: () -> Unit, onStartDaemon: () -> Unit) {
    when (update) {
        ModuleUpdate.Idle -> Unit

        ModuleUpdate.Updating -> StatusCard(
            icon = Icons.Outlined.SystemUpdate,
            title = stringResource(R.string.updating_module),
            body = null,
            error = false,
            busy = true,
        )

        ModuleUpdate.Disabled -> StatusCard(
            icon = Icons.Outlined.SystemUpdate,
            title = stringResource(R.string.module_disabled),
            body = null,
            error = false,
        )

        ModuleUpdate.NotReporting -> StatusCard(
            icon = Icons.Filled.ErrorOutline,
            title = stringResource(R.string.status_stopped),
            body = stringResource(R.string.daemon_not_reporting),
            error = true,
            action = stringResource(R.string.start_daemon),
            onAction = onStartDaemon,
        )

        is ModuleUpdate.Failed -> StatusCard(
            icon = Icons.Filled.ErrorOutline,
            title = stringResource(R.string.update_failed_title),
            body = installerError(update.output),
            error = true,
            action = stringResource(UiR.string.retry),
            onAction = onUpdateModule,
        )
    }
}

/**
 * A persistent notice at the top of the overview. A fault uses the error
 * colors; something that only needs attention, the tertiary ones.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    body: String?,
    error: Boolean,
    action: String? = null,
    busy: Boolean = false,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (error) colors.errorContainer else colors.tertiaryContainer,
            contentColor = if (error) colors.onErrorContainer else colors.onTertiaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    LoadingIndicator(Modifier.size(32.dp))
                } else if (action != null) {
                    Button(
                        onClick = onAction,
                        modifier = Modifier.padding(top = 8.dp),
                        colors = if (error) {
                            ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)
                        } else {
                            ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary)
                        },
                    ) {
                        Text(action)
                    }
                }
            }
        }
    }
}

/**
 * Why an installation failed, from the installer's output: the lines
 * Magisk and KernelSU mark with "!" for errors, else the last line. The
 * rest is the installer's banner and progress.
 */
internal fun installerError(output: String): String? {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val errors = lines.filter { it.startsWith("!") }.map { it.removePrefix("!").trim() }
    return errors.ifEmpty { listOfNotNull(lines.lastOrNull()) }.joinToString("\n").ifEmpty { null }
}

/** Shown when the module is absent: offers to flash the copy bundled in this APK. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModuleSetupMessage(setup: ModuleSetup, onInstall: () -> Unit, onRetry: () -> Unit) {
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
            body = installerError(setup.output).orEmpty(),
            actionLabel = stringResource(UiR.string.retry),
            onAction = onInstall,
        )
    }
}
