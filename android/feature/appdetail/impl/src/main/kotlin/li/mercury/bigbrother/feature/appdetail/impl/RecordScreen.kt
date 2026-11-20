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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.ClipboardUse
import li.mercury.bigbrother.core.model.clipboardUse
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.LocalWallClock
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.appDisplayName
import li.mercury.bigbrother.core.ui.exactDateTime
import li.mercury.bigbrother.core.ui.relativeTime
import li.mercury.bigbrother.core.ui.topicIcon
import li.mercury.bigbrother.core.ui.topicRecordsSummary
import li.mercury.bigbrother.core.ui.topicTitle
import li.mercury.bigbrother.core.ui.R as UiR

/**
 * One kind of data an app accessed: what exactly, when it last happened and
 * each time it did. Shown in a bottom sheet over the app's page in compact
 * windows, and as a pane beside it in wider ones.
 */
@Composable
fun RecordScreen(uid: Int, packageName: String, onClose: () -> Unit, viewModel: RecordViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RecordScreen(uiState, uid, packageName, onClose, viewModel::retry)
}

/** Details listed before the rest are asked for. */
private const val DETAILS_SHOWN = 8

/** Sensor sessions up to which every sensor's are shown open. */
private const val SESSIONS_SHOWN_OPEN = 12

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RecordScreen(
    state: RecordUiState,
    uid: Int,
    packageName: String,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    val group = state.group
    val clock = LocalWallClock.current
    // Sessions still going are counted up to when they were loaded.
    val now = remember(state.sensorSessions, clock) { clock.millis() / 1000 }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // As a pane it reaches the top of the window, under the status bar; in a
    // bottom sheet it starts below it.
    val top = if (LocalShowsDetailBesideList.current) Modifier.statusBarsPadding() else Modifier
    LazyColumn(
        modifier = top,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        item(key = "header") {
            Header(state, uid, packageName, onClose)
        }
        when {
            state.failure != null -> item(key = "failure") {
                LoadFailureMessage(state.failure, onRetry, Modifier.fillParentMaxHeight(0.5f))
            }

            !state.loaded -> item(key = "loading") {
                BigBrotherLoadingIndicator(Modifier.fillMaxWidth().padding(vertical = 48.dp))
            }

            // Records age out of the range, so a link can outlive them.
            group == null -> item(key = "gone") {
                Message(stringResource(UiR.string.empty_title), "", Modifier.fillParentMaxHeight(0.5f))
            }

            else -> {
                val details = recordDetails(group.accesses.map { it.path }, titled = topicTitle(group.topic) != null)
                    .filterIsInstance<RecordDetail.Plain>()
                if (details.isNotEmpty()) {
                    item(key = "details-header") { SectionHeader(stringResource(R.string.record_details)) }
                    item(key = "details") { Details(details) }
                }
                val sessions = state.sensorSessions
                if (!sessions.isNullOrEmpty()) {
                    val groups = groupSessions(sessions)
                    // A few are shown open; many, each sensor folded.
                    val open = sessions.size <= SESSIONS_SHOWN_OPEN
                    item(key = "sensors-header") { SectionHeader(stringResource(R.string.sensor_sessions)) }
                    for (sensorGroup in groups) {
                        item(key = "sensor:${sensorGroup.sensor}") {
                            SensorGroup(sensorGroup, now, initiallyExpanded = open, modifier = Modifier.padding(bottom = 8.dp))
                        }
                    }
                    item(key = "sensors-note") { SensorSessionsNote() }
                }
                val periods = state.periods
                item(key = "history-header") { SectionHeader(stringResource(R.string.record_history)) }
                if (state.historyFailure != null) {
                    item(key = "history-failure") { HistoryFailure(onRetry) }
                } else if (periods == null) {
                    item(key = "history-loading") {
                        BigBrotherLoadingIndicator(Modifier.fillMaxWidth().padding(vertical = 24.dp))
                    }
                } else {
                    itemsIndexed(periods, key = { index, _ -> "period:$index" }) { index, period ->
                        PeriodItem(period, group.fromPermissions, index, periods.size)
                    }
                    if (state.partialHistory) {
                        item(key = "partial") {
                            Text(
                                text = stringResource(R.string.record_history_partial),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The history could not be loaded, though the record itself could. */
@Composable
private fun HistoryFailure(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(UiR.string.error_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(UiR.string.retry)) }
    }
}

/** What the record is, whose, how often and when last; and a way to close it. */
@Composable
private fun Header(state: RecordUiState, uid: Int, packageName: String, onClose: () -> Unit) {
    val group = state.group
    Column(
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val title = group?.let { topicTitle(it.topic) }
            ShapedIcon(topicIcon(group?.topic?.takeIf { title != null }), size = 48.dp, iconSize = 24.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        title != null -> stringResource(title)
                        group != null -> group.topic
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                val app = appDisplayName(AppSummary(uid, packageName, emptyMap(), 0), state.appInfo)
                Text(
                    text = group?.let { stringResource(R.string.header_subtitle, app, stringResource(it.category.title)) }
                        ?: app,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
        if (group != null) {
            val clock = LocalWallClock.current
            val asked = remember(group) { appsAsked(group) }
            val last = remember(group, clock) { relativeTime(group.lastSeen, clock.millis()) }
            val records = remember(group) { group.records }
            val summary = if (group.allowed == 0) {
                topicRecordsSummary(group.topic, records)
                    ?: pluralStringResource(R.plurals.total_blocked, group.denied, group.denied)
            } else {
                outcome(group, group.allowed, asked)
            } + stringResource(R.string.time_separator) + last
            if (group.allowed == 0) DeniedText(summary) else Text(summary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** What exactly was accessed: commands run, addresses contacted. */
@Composable
private fun Details(details: List<RecordDetail.Plain>) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) details else details.take(DETAILS_SHOWN)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (detail in shown) {
                    Text(detail.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (shown.size < details.size) {
            TextButton(onClick = { showAll = true }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.show_all_details, details.size))
            }
        }
    }
}

/** What an access to the clipboard did, as a verb: "Read", "Watched", "Written". */
private val ClipboardUse.label: Int
    get() = when (this) {
        ClipboardUse.READ -> R.string.action_read
        ClipboardUse.LISTEN -> R.string.action_watch
        ClipboardUse.WRITE -> R.string.action_clipboard_write
    }

/** One access, or several in the same second. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PeriodItem(period: AccessPeriod, fromPermissions: Boolean, index: Int, count: Int) {
    // The system's permission records say a permission was used, not how;
    // the clipboard's say which way.
    val clipboard = period.subject?.let(::clipboardUse)
    val label = when {
        clipboard != null -> stringResource(clipboard.label)
        fromPermissions -> stringResource(R.string.action_used)
        else -> actionLabel(period.action, period.viaProvider)
    }
    ListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        supportingContent = {
            when {
                period.allowed == 0 -> DeniedText(stringResource(R.string.access_blocked, label, period.denied))

                period.denied > 0 -> Text(
                    stringResource(
                        R.string.access_partly_blocked,
                        label,
                        period.allowed + period.denied,
                        period.denied,
                    ),
                )

                else -> Text(stringResource(R.string.access_allowed, label, period.allowed))
            }
        },
    ) {
        Text(exactDateTime(period.time))
    }
}
