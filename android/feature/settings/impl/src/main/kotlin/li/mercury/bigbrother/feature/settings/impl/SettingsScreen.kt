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

package li.mercury.bigbrother.feature.settings.impl

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RuleFolder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.data.repository.UserSettingsWriter
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.rememberSettingsWriteSnackbar
import li.mercury.bigbrother.core.ui.R as UiR

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsWriteFailures by viewModel.settingsWriteFailures.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    // This app's own version, from the package manager here rather than in
    // the stateless screen, which tests render without this app installed.
    val context = LocalContext.current
    val appVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    SettingsScreen(
        state = uiState,
        appVersion = appVersion,
        onBack = onBack,
        onShowSystemApps = viewModel::setShowSystemApps,
        onUseDynamicColor = viewModel::setUseDynamicColor,
        onExportLogs = viewModel::exportLogs,
        onDismissLogExport = viewModel::dismissLogExport,
        settingsWriteFailure = settingsWriteFailures.firstOrNull(),
        onSettingsWriteResult = viewModel::resolveSettingsWrite,
    )
}

/** Name of a kind of activity the daemon reports as `coverage_<id>`. */
private fun featureLabel(id: String): Int? = when (id) {
    "files" -> R.string.feature_files
    "exec" -> R.string.feature_exec
    "network" -> R.string.feature_network
    "process" -> R.string.feature_process
    "netlink" -> R.string.feature_netlink
    "binder" -> R.string.feature_binder
    "appops" -> R.string.feature_appops
    else -> null
}

private const val SOURCE_URL = "https://github.com/lihenggui/bigbrother"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    appVersion: String,
    onBack: () -> Unit,
    onShowSystemApps: (Boolean) -> Unit,
    onUseDynamicColor: (Boolean) -> Unit,
    onExportLogs: () -> Unit,
    onDismissLogExport: () -> Unit,
    settingsWriteFailure: UserSettingsWriter.Failure? = null,
    onSettingsWriteResult: (UserSettingsWriter.Failure, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val exported = state.logExport as? LogExport.Ready
    LaunchedEffect(exported) {
        if (exported == null) return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", exported.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // As ClipData too, so that the share sheet itself may read the file
        // to show it, not only the app it is shared with.
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, null))
        onDismissLogExport()
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    val snackbar = rememberSettingsWriteSnackbar(settingsWriteFailure, onSettingsWriteResult)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val status = state.status
        val unavailable = status?.unavailable.orEmpty().map { id -> featureLabel(id)?.let { stringResource(it) } ?: id }
        LazyColumn(
            // Settings read best in a column of moderate width.
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "recording") { SectionHeader(stringResource(R.string.section_recording)) }
            val recordingRows = 2 + if (unavailable.isNotEmpty()) 1 else 0
            item(key = "status") {
                Row(
                    icon = if (status?.recording == false) Icons.Outlined.ErrorOutline else Icons.Outlined.RadioButtonChecked,
                    iconTint = if (status?.recording == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    title = stringResource(
                        when (status?.recording) {
                            true -> R.string.recording_active
                            false -> R.string.recording_stopped
                            null -> R.string.recording_unknown
                        },
                    ),
                    summary = listOfNotNull(
                        state.moduleVersion?.let { stringResource(R.string.module_version, it) },
                        stringResource(R.string.retention),
                    ).joinToString(stringResource(UiR.string.dot_separator)),
                    index = 0,
                    count = recordingRows,
                )
            }
            if (unavailable.isNotEmpty()) {
                item(key = "coverage") {
                    Row(
                        icon = Icons.Outlined.RuleFolder,
                        title = stringResource(R.string.coverage_title),
                        summary = unavailable.joinToString(stringResource(UiR.string.summary_separator)),
                        index = 1,
                        count = recordingRows,
                    )
                }
            }
            item(key = "lsposed") {
                Row(
                    icon = Icons.Outlined.Extension,
                    title = stringResource(R.string.lsposed_title),
                    summary = stringResource(R.string.lsposed_summary),
                    index = recordingRows - 1,
                    count = recordingRows,
                )
            }

            item(key = "display") { SectionHeader(stringResource(R.string.section_display)) }
            item(key = "system-apps") {
                SwitchRow(
                    icon = Icons.Outlined.VisibilityOff,
                    title = stringResource(R.string.setting_system_apps),
                    summary = stringResource(R.string.setting_system_apps_summary),
                    checked = state.userData.showSystemApps,
                    onCheckedChange = onShowSystemApps,
                    index = 0,
                    count = 2,
                )
            }
            item(key = "dynamic-color") {
                SwitchRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.dynamic_color),
                    summary = stringResource(R.string.dynamic_color_summary),
                    checked = state.userData.useDynamicColor,
                    onCheckedChange = onUseDynamicColor,
                    index = 1,
                    count = 2,
                )
            }

            item(key = "about") { SectionHeader(stringResource(R.string.section_about)) }
            item(key = "version") {
                Row(icon = Icons.Outlined.Info, title = stringResource(R.string.version), summary = appVersion, index = 0, count = 4)
            }
            item(key = "export-logs") {
                Row(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.export_logs),
                    summary = stringResource(
                        when (state.logExport) {
                            LogExport.Collecting -> R.string.export_logs_collecting
                            LogExport.Failed -> R.string.export_logs_failed
                            else -> R.string.export_logs_summary
                        },
                    ),
                    index = 1,
                    count = 4,
                    onClick = onExportLogs,
                )
            }
            item(key = "source") {
                Row(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.source_code),
                    summary = SOURCE_URL.removePrefix("https://"),
                    index = 2,
                    count = 4,
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            item(key = "license") {
                Row(icon = Icons.Outlined.Gavel, title = stringResource(R.string.license), summary = "GPL-3.0", index = 3, count = 4)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Row(
    icon: ImageVector,
    title: String,
    summary: String,
    index: Int,
    count: Int,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    val leading = @Composable { Icon(icon, contentDescription = null, tint = iconTint) }
    val supporting = @Composable { Text(summary) }
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(),
            leadingContent = leading,
            supportingContent = supporting,
        ) { Text(title) }
    } else {
        ListItem(
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(),
            leadingContent = leading,
            supportingContent = supporting,
        ) { Text(title) }
    }
}

/**
 * A setting that takes effect at once, as Material asks of a switch. The
 * whole row toggles it; the switch alone shows the state, so the row keeps
 * its color.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    ListItem(
        modifier = Modifier
            .clip(if (count == 1) MaterialTheme.shapes.large else segmentShape(index, count))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(title) }
}

/** The shape of a row in a segmented group, for clipping its touch feedback. */
@Composable
private fun segmentShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 16.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}
