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

package li.mercury.bigbrother.feature.jail.impl.network

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.NetworkActivity
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.MenuFilterChip
import li.mercury.bigbrother.core.ui.MenuOption
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.timeWithDateIfNotToday
import li.mercury.bigbrother.feature.jail.impl.R
import li.mercury.bigbrother.feature.jail.impl.SandboxWarningCard
import java.text.NumberFormat

/** What is not as usual for the app or the device, before anything else. */
internal fun LazyListScope.notices(activity: NetworkActivity, actions: NetworkActivityActions) {
    if (!activity.recording.on) {
        item(key = "stopped") {
            val last = (activity.domains + activity.addresses).maxOfOrNull { it.last } ?: activity.until
            NoticeCard(
                icon = Icons.Outlined.History,
                title = null,
                body = stringResource(R.string.jail_net_stopped, timeWithDateIfNotToday(last)),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                action = stringResource(R.string.jail_net_start) to { actions.onSetRecording(true) },
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            )
        }
    }
    if (!activity.networkAllowed) {
        item(key = "no_network") {
            NoticeCard(
                icon = Icons.Outlined.Block,
                title = stringResource(R.string.jail_net_no_network_title),
                body = stringResource(R.string.jail_net_no_network_body),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            )
        }
    }
    if (!activity.names) {
        item(key = "names") {
            SandboxWarningCard(
                title = stringResource(R.string.jail_net_names_missing_title),
                body = stringResource(R.string.jail_net_names_missing_body),
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme).padding(bottom = 8.dp),
            )
        }
    }
}

/** A note about the page as a whole, with an action if it calls for one. */
@Composable
private fun NoticeCard(
    icon: ImageVector,
    title: String?,
    body: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (action != null) 4.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    TextButton(onClick = action.second, modifier = Modifier.align(Alignment.End)) { Text(action.first) }
                }
            }
        }
    }
}

/** Recording, with nothing recorded yet: since when, and no empty chart. */
@Composable
internal fun Empty(since: Long, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShapedIcon(Icons.Outlined.Public, size = 72.dp, iconSize = 32.dp)
        Text(stringResource(R.string.jail_net_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.jail_net_empty_body, timeWithDateIfNotToday(since)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * How many destinations, connections and how much data, then the chart.
 * Choosing another range cross-fades it rather than growing the bars.
 */
@Composable
internal fun Summary(state: NetworkActivityUiState, activity: NetworkActivity, onSelect: (Long?) -> Unit, modifier: Modifier = Modifier) {
    val totals = state.totals
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright, modifier = modifier.fillMaxWidth()) {
        val enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())
        val exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
        AnimatedContent(
            targetState = activity,
            contentKey = { it.bucketSeconds to it.until - it.since },
            transitionSpec = { enter togetherWith exit },
            label = "summary",
        ) { shown ->
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Choosing a bar changes the numbers in place, with a cross-fade.
                FadeThrough(totals, kind = { it }) { Totals(it, traffic = shown.traffic && shown.networkAllowed) }
                ActivityChart(
                    buckets = shown.buckets,
                    bucketSeconds = shown.bucketSeconds,
                    zone = shown.zone,
                    selected = state.bucket,
                    onSelect = onSelect,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ChartLegend(Modifier.padding(top = 8.dp))
                if (!shown.traffic) {
                    Text(
                        stringResource(R.string.jail_net_traffic_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** How many destinations, and how many connections and how much data went to them. */
@Composable
private fun Totals(totals: ActivityTotals, traffic: Boolean) {
    val numbers = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(numbers.format(totals.destinations), style = MaterialTheme.typography.displayMedium, modifier = Modifier.alignByBaseline())
            Text(
                pluralStringResource(R.plurals.jail_net_destinations, totals.destinations),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Text(
            if (traffic) {
                pluralStringResource(
                    R.plurals.jail_net_facts,
                    totals.connections,
                    numbers.format(totals.connections),
                    formatBytes(totals.received),
                    formatBytes(totals.sent),
                )
            } else {
                pluralStringResource(R.plurals.jail_net_facts_counts, totals.connections, numbers.format(totals.connections), numbers.format(totals.refused))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The chosen period, the blocked filter and the order. */
@Composable
internal fun Filters(state: NetworkActivityUiState, actions: NetworkActivityActions, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The period chip brings its own gap, so that none is left without it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            PeriodChip(state, onClear = { actions.onSelectBucket(null) })
            FilterChip(
                // The check mark widens the chip as it comes.
                modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec()),
                selected = state.blockedOnly,
                onClick = { actions.onBlockedOnly(!state.blockedOnly) },
                label = { Text(stringResource(R.string.jail_net_filter_blocked_count, state.blockedCount)) },
                leadingIcon = if (state.blockedOnly) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
        MenuFilterChip(
            label = stringResource(state.sort.label),
            options = DestinationSort.entries.map { MenuOption(it, stringResource(it.label)) },
            selected = state.sort,
            onSelect = actions.onSort,
        )
    }
}

/**
 * The bar chosen in the chart, which clears the choice. It grows in beside
 * the other filters, and keeps its label while it leaves.
 */
@Composable
private fun PeriodChip(state: NetworkActivityUiState, onClear: () -> Unit) {
    val bucket = state.bucket
    val activity = state.activity
    val label = if (bucket != null && activity != null) periodLabel(bucket, activity.bucketSeconds, activity.zone) else null
    val transition = updateTransition(label, label = "period")
    val motion = MaterialTheme.motionScheme
    transition.AnimatedVisibility(
        visible = { it != null },
        enter = expandHorizontally(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkHorizontally(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) {
        InputChip(
            selected = true,
            onClick = onClear,
            label = { Text(transition.targetState ?: transition.currentState.orEmpty()) },
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

private val DestinationSort.label: Int
    get() = when (this) {
        DestinationSort.DATA -> R.string.jail_net_sort_data
        DestinationSort.CONNECTIONS -> R.string.jail_net_sort_connections
        DestinationSort.RECENT -> R.string.jail_net_sort_recent
        DestinationSort.NAME -> R.string.jail_net_sort_name
    }

@Composable
internal fun ShowAll(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(stringResource(R.string.jail_net_show_all, count))
        Spacer(Modifier.size(6.dp))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null)
    }
}
