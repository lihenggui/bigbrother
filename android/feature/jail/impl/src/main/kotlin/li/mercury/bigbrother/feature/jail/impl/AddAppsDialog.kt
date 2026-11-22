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

package li.mercury.bigbrother.feature.jail.impl

import android.view.WindowInsetsController
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.window.core.layout.WindowSizeClass
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.segmentShape
import li.mercury.bigbrother.core.model.InstalledApp
import li.mercury.bigbrother.core.ui.AppIcon
import li.mercury.bigbrother.core.ui.ContentKind
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.failureReason
import li.mercury.bigbrother.core.ui.rememberPredictiveDismissProgress
import li.mercury.bigbrother.core.ui.R as UiR

/**
 * The installed apps, to choose those to put in the sandbox. Full-screen on
 * a phone, a dialog on wider windows. The search field edits [query]
 * directly; [state] lists the apps that match it once they are worked out.
 */
@Composable
internal fun AddAppsDialog(
    state: AddAppsUiState,
    query: TextFieldState,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
) {
    // Material keeps full-screen dialogs to compact windows.
    val compact = !currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    // Full-screen, it covers the system bars too, as the screen behind it does.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = !compact,
        ),
    ) {
        val progress by rememberPredictiveDismissProgress(onDismiss)
        AddAppsSheet(
            state,
            query,
            compact,
            onToggle,
            onDismiss,
            onConfirm,
            onRetry,
            modifier = Modifier.graphicsLayer {
                val fraction = PredictiveBackEasing.transform(progress)
                scaleX = 1f - 0.1f * fraction
                scaleY = scaleX
            },
        )
    }
}

private val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/** What the dialog shows: the whole window when [compact], a card otherwise. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AddAppsSheet(
    state: AddAppsUiState,
    query: TextFieldState,
    compact: Boolean,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (compact) SystemBarsOver(MaterialTheme.colorScheme.surfaceContainer)
    Surface(
        modifier = if (compact) {
            modifier.fillMaxSize()
        } else {
            modifier
                .padding(24.dp)
                .widthIn(max = 560.dp)
                .heightIn(max = 640.dp)
        },
        shape = if (compact) RectangleShape else MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(if (compact) Modifier.systemBarsPadding().imePadding() else Modifier) {
            AddAppsContent(state, query, onToggle, onDismiss, onConfirm, onRetry)
        }
    }
}

/**
 * Has the dialog's window draw the system bars' icons dark over a light
 * [background], as the app's own window does; the window starts with
 * light icons, unreadable there.
 */
@Composable
private fun SystemBarsOver(background: Color) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val light = background.luminance() > 0.5f
    SideEffect {
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window?.insetsController?.setSystemBarsAppearance(if (light) flags else 0, flags)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.AddAppsContent(
    state: AddAppsUiState,
    query: TextFieldState,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
) {
    AddAppsHeader(state.selected.size, onDismiss, onConfirm)
    AddAppsSearch(query)
    AddAppsBody(state, onToggle, onRetry)
    HorizontalDivider()
    AddAppsFooter()
}

@Composable
private fun AddAppsHeader(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.jail_close))
        }
        Text(
            stringResource(R.string.jail_add_apps),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        Button(onClick = onConfirm, enabled = count > 0) {
            Text(
                if (count == 0) {
                    stringResource(R.string.jail_add_confirm_none)
                } else {
                    pluralStringResource(R.plurals.jail_add_confirm, count, count)
                },
            )
        }
    }
}

@Composable
private fun AddAppsSearch(query: TextFieldState) {
    TextField(
        state = query,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.jail_add_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun ColumnScope.AddAppsBody(state: AddAppsUiState, onToggle: (String) -> Unit, onRetry: () -> Unit) {
    FadeThrough(state, kind = { it.kind() }, modifier = Modifier.weight(1f)) { shown ->
        val apps = shown.apps
        when {
            shown.failure != null -> Message(
                title = failureReason(shown.failure),
                body = "",
                actionLabel = stringResource(UiR.string.retry),
                onAction = onRetry,
            )

            apps == null -> BigBrotherLoadingIndicator(Modifier.fillMaxSize())

            apps.isEmpty() -> Text(
                stringResource(R.string.jail_add_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
            )

            else -> AddAppsList(apps, shown.selected, onToggle, Modifier.fillMaxSize())
        }
    }
}

private fun AddAppsUiState.kind(): ContentKind = when {
    apps?.isEmpty() == true && failure == null -> ContentKind.Empty
    else -> ContentKind.of(failure, apps)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddAppsList(apps: List<InstalledApp>, selected: Set<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            val checked = app.packageName in selected
            ListItem(
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme)
                    .clip(segmentShape(index, apps.size))
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(app.packageName) }),
                shapes = ListItemDefaults.segmentedShapes(index, apps.size),
                colors = ListItemDefaults.segmentedColors(),
                leadingContent = { AppIcon(app.info, app.info.label, 40.dp) },
                supportingContent = { Text(app.packageName) },
                trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                verticalAlignment = Alignment.CenterVertically,
            ) { Text(app.info.label) }
        }
    }
}

@Composable
private fun AddAppsFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.RestartAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.jail_add_restart),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
