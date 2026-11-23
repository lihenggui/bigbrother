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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RuleFolder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.SegmentedSwitchRow
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.UserSettingsWriter
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.animatedSegmentedShapes
import li.mercury.bigbrother.core.ui.failureReason
import li.mercury.bigbrother.core.ui.rememberSettingsWriteSnackbar
import li.mercury.bigbrother.core.ui.R as UiR

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
        onRemoveModule = viewModel::removeModule,
        onSettingsWriteResult = viewModel::onMessageShown,
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
    onRemoveModule: () -> Unit,
    onSettingsWriteResult: (UserSettingsWriter.Failure, Boolean) -> Unit = { _, _ -> },
    initiallyConfirmingRemoval: Boolean = false,
) {
    val context = LocalContext.current
    ShareLogs(state.logExport as? LogExport.Ready, onDismissLogExport)
    var confirmingRemoval by rememberSaveable { mutableStateOf(initiallyConfirmingRemoval) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    val snackbar = rememberSettingsWriteSnackbar(state.message, onSettingsWriteResult)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SettingsTopBar(background, scrollBehavior, onBack)
        },
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val unavailable = state.status?.unavailable.orEmpty().map { id -> featureLabel(id)?.let { stringResource(it) } ?: id }
        LazyColumn(
            // Settings read best in a column of moderate width.
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            recorderSection(state, unavailable, onRemoveModule = { confirmingRemoval = true })
            displaySection(state, onShowSystemApps, onUseDynamicColor)
            aboutSection(state.logExport, appVersion, onExportLogs) {
                context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    if (confirmingRemoval) {
        RemoveModuleDialog(
            onDismiss = { confirmingRemoval = false },
            onConfirm = {
                confirmingRemoval = false
                onRemoveModule()
            },
        )
    }
}

@Composable
private fun RemoveModuleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_module_title)) },
        text = { Text(stringResource(R.string.remove_module_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove_module_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remove_module_cancel)) }
        },
    )
}

@Composable
private fun ShareLogs(exported: LogExport.Ready?, onDismissLogExport: () -> Unit) {
    val context = LocalContext.current
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsTopBar(background: Color, scrollBehavior: TopAppBarScrollBehavior, onBack: () -> Unit) {
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
}

private fun LazyListScope.recorderSection(state: SettingsUiState, unavailable: List<String>, onRemoveModule: () -> Unit) {
    val status = state.status
    item(key = "recording") { SectionHeader(stringResource(R.string.section_recording), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    val lsposedIndex = 1 + if (unavailable.isNotEmpty()) 1 else 0
    val recordingRows = lsposedIndex + 1 + if (state.moduleRemovable) 1 else 0
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
                state.moduleVersionFailure?.let { failureReason(it) },
                state.recorderStatusFailure?.let { failureReason(it) },
                stringResource(R.string.retention),
            ).distinct().joinToString(stringResource(UiR.string.dot_separator)),
            index = 0,
            count = recordingRows,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
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
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            )
        }
    }
    item(key = "lsposed") {
        Row(
            icon = Icons.Outlined.Extension,
            title = stringResource(R.string.lsposed_title),
            summary = stringResource(R.string.lsposed_summary),
            index = lsposedIndex,
            count = recordingRows,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        )
    }
    if (state.moduleRemovable) {
        item(key = "remove-module") {
            val removal = state.moduleRemoval
            Row(
                icon = Icons.Outlined.Delete,
                title = stringResource(R.string.remove_module),
                summary = when (removal) {
                    ModuleRemoval.Removing -> stringResource(R.string.remove_module_removing)
                    is ModuleRemoval.Failed -> failureReason(removal.failure)
                    ModuleRemoval.Idle -> stringResource(R.string.remove_module_summary)
                },
                index = recordingRows - 1,
                count = recordingRows,
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
                onClick = onRemoveModule.takeIf { removal != ModuleRemoval.Removing },
            )
        }
    }
}

private fun LazyListScope.displaySection(
    state: SettingsUiState,
    onShowSystemApps: (Boolean) -> Unit,
    onUseDynamicColor: (Boolean) -> Unit,
) {
    item(key = "display") { SectionHeader(stringResource(R.string.section_display), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    item(key = "system-apps") {
        SegmentedSwitchRow(
            leadingContent = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
            title = stringResource(R.string.setting_system_apps),
            summary = stringResource(R.string.setting_system_apps_summary),
            checked = state.userData.showSystemApps,
            onCheckedChange = onShowSystemApps,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            index = 0,
            count = 2,
        )
    }
    item(key = "dynamic-color") {
        SegmentedSwitchRow(
            leadingContent = { Icon(Icons.Outlined.Palette, contentDescription = null) },
            title = stringResource(R.string.dynamic_color),
            summary = stringResource(R.string.dynamic_color_summary),
            checked = state.userData.useDynamicColor,
            onCheckedChange = onUseDynamicColor,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            index = 1,
            count = 2,
        )
    }
}

private fun LazyListScope.aboutSection(
    export: LogExport?,
    appVersion: String,
    onExportLogs: () -> Unit,
    onOpenSource: () -> Unit,
) {
    item(key = "about") { SectionHeader(stringResource(R.string.section_about), Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    item(key = "version") {
        Row(icon = Icons.Outlined.Info, title = stringResource(R.string.version), summary = appVersion, index = 0, count = 4, modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme))
    }
    item(key = "export-logs") {
        Row(
            icon = Icons.Outlined.BugReport,
            title = stringResource(R.string.export_logs),
            summary = when (export) {
                LogExport.Collecting -> stringResource(R.string.export_logs_collecting)
                is LogExport.Failed -> failureReason(export.failure)
                else -> stringResource(R.string.export_logs_summary)
            },
            index = 1,
            count = 4,
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
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
            modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            onClick = onOpenSource,
        )
    }
    item(key = "license") {
        Row(icon = Icons.Outlined.Gavel, title = stringResource(R.string.license), summary = "GPL-3.0", index = 3, count = 4, modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme))
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
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    // What the recorder reports arrives after the screen opens: the row
    // fades to it in place, and its corners follow rows joining the group.
    val tint by animateColorAsState(iconTint, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "tint")
    val shapes = animatedSegmentedShapes(index, count)
    val leading = @Composable { FadeThrough(icon, kind = { it }) { Icon(it, contentDescription = null, tint = tint) } }
    val supporting = @Composable { FadeThrough(summary, kind = { it }) { Text(it) } }
    val headline = @Composable { FadeThrough(title, kind = { it }) { Text(it) } }
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            modifier = modifier,
            shapes = shapes,
            colors = ListItemDefaults.segmentedColors(),
            leadingContent = leading,
            supportingContent = supporting,
            content = headline,
        )
    } else {
        ListItem(
            modifier = modifier,
            shapes = shapes,
            colors = ListItemDefaults.segmentedColors(),
            leadingContent = leading,
            supportingContent = supporting,
            content = headline,
        )
    }
}
