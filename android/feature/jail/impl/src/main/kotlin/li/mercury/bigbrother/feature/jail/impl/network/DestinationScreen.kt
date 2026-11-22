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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.model.DestinationActivity
import li.mercury.bigbrother.core.model.DestinationDetail
import li.mercury.bigbrother.core.model.NetworkTarget
import li.mercury.bigbrother.core.model.PortActivity
import li.mercury.bigbrother.core.ui.FadeThrough
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.animatedRow
import li.mercury.bigbrother.core.ui.timeWithDateIfNotToday
import li.mercury.bigbrother.feature.jail.impl.ErrorEffect
import li.mercury.bigbrother.feature.jail.impl.R
import java.text.NumberFormat

/** Addresses listed before the rest are counted. */
private const val ADDRESSES_SHOWN = 3

/**
 * One destination of an app: what went to it hour by hour, its names, ports
 * and addresses, and blocking it whole or a part of it. Shown in a bottom
 * sheet over the activity in compact and medium windows, as a pane beside
 * it in wider ones.
 */
@Composable
fun DestinationScreen(onClose: () -> Unit, viewModel: DestinationViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DestinationScreen(
        state = state,
        onClose = onClose,
        onRetry = viewModel::refresh,
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
        onUndo = viewModel::undo,
        onMessageDismiss = viewModel::dismissMessage,
        onErrorDismiss = viewModel::dismissError,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DestinationScreen(
    state: DestinationUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onBlock: (NetworkTarget) -> Unit,
    onUnblock: (NetworkTarget) -> Unit,
    onUndo: () -> Unit,
    onMessageDismiss: () -> Unit,
    onErrorDismiss: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    ErrorEffect(state.error, snackbar, onErrorDismiss)
    MessageEffect(state.message, snackbar, onUndo, onMessageDismiss)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // As a pane it reaches the top of the window, under the status bar; in a
    // bottom sheet it starts below it.
    val top = if (LocalShowsDetailBesideList.current) Modifier.statusBarsPadding() else Modifier
    val toggle = { target: NetworkTarget, block: Boolean -> if (block) onBlock(target) else onUnblock(target) }
    Box(top) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + bottom),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            val destination = state.destination
            item(key = "header") { Header(state.target, destination, onClose, Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
            when {
                state.failure != null -> item(key = "failure") {
                    LoadFailureMessage(state.failure, onRetry, Modifier.animatedRow(this, MaterialTheme.motionScheme).fillParentMaxHeight(0.5f))
                }

                destination == null -> item(key = "loading") {
                    BigBrotherLoadingIndicator(Modifier.animatedRow(this, MaterialTheme.motionScheme).fillMaxWidth().padding(vertical = 48.dp))
                }

                else -> details(state, destination, toggle)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
    }
}

private fun LazyListScope.details(
    state: DestinationUiState,
    destination: DestinationActivity,
    toggle: (NetworkTarget, Boolean) -> Unit,
) {
    val target = destination.target
    item(key = "action") { MainAction(destination, toggle, Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    val limits = buildList {
        if (target is NetworkTarget.Domain && state.sharedWith.isNotEmpty()) add(state.sharedWith)
        if (target is NetworkTarget.Domain && state.ownResolver) add(null)
    }
    if (limits.isNotEmpty()) {
        item(key = "limits") {
            Column(Modifier.animatedRow(this, MaterialTheme.motionScheme).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (limit in limits) {
                    Limit(
                        if (limit != null) {
                            stringResource(R.string.jail_net_shared, limit.joinToString(stringResource(R.string.jail_list_separator)))
                        } else {
                            stringResource(R.string.jail_net_own_resolver)
                        },
                    )
                }
            }
        }
    }
    item(key = "stats") {
        // The hourly detail loads after the destination: it fades in and the rows below make room.
        FadeThrough(state.detail, kind = { it != null }, modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme)) { detail -> detail?.let { Stats(destination, it) } }
    }
    val site = (target as? NetworkTarget.Domain)?.name
    // A site seen under its own name alone has no names to list, unless that
    // name is blocked by itself: the rule is shown where it can be removed.
    val hosts = destination.hosts.takeIf { hosts ->
        val only = hosts.singleOrNull()
        hosts.size > 1 || only?.name != site || (only?.rule != null && destination.rule == null)
    }.orEmpty()
    if (target is NetworkTarget.Domain && hosts.isNotEmpty()) {
        item(key = "hosts") { SectionHeader(stringResource(R.string.jail_net_hosts_header), Modifier.animatedRow(this, MaterialTheme.motionScheme), trailing = hosts.size.toString()) }
        itemsIndexed(hosts, key = { _, host -> "host:${host.name}" }) { index, host ->
            DestinationRow(
                row = DestinationRow(
                    key = host.name,
                    title = host.name,
                    isAddress = false,
                    opens = null,
                    toggle = host.rule ?: NetworkTarget.Domain(host.name, exact = true),
                    blocked = host.rule != null,
                    connections = host.connections,
                    refused = host.refused,
                    sent = host.sent,
                    received = host.received,
                ),
                onOpen = null,
                onToggle = { block -> toggle(host.rule ?: NetworkTarget.Domain(host.name, exact = true), block) },
                index = index,
                count = hosts.size,
                modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
            )
        }
    }
    if (destination.ports.isNotEmpty()) {
        item(key = "ports") { SectionHeader(stringResource(R.string.jail_net_ports_header), Modifier.animatedRow(this, MaterialTheme.motionScheme), trailing = destination.ports.size.toString()) }
        itemsIndexed(destination.ports, key = { _, port -> "port:${port.port}" }) { index, port ->
            // Ports are blocked one by one only on an address: a domain's go with its addresses.
            val portTarget = (target as? NetworkTarget.Address)?.let { port.rule ?: NetworkTarget.Address(it.ip, port.port) }
            PortRow(port, portTarget?.let { { block: Boolean -> toggle(it, block) } }, index, destination.ports.size, Modifier.animatedRow(this, MaterialTheme.motionScheme))
        }
    }
    if (destination.addresses.isNotEmpty()) {
        item(key = "addresses") { SectionHeader(stringResource(R.string.jail_net_addresses_header), Modifier.animatedRow(this, MaterialTheme.motionScheme), trailing = destination.addressCount.toString()) }
        item(key = "address_list") { Addresses(destination.addresses, destination.addressCount, Modifier.animatedRow(this, MaterialTheme.motionScheme)) }
    }
}

/** The name, whether it is blocked, how many names it has and when it was reached; and closing it. */
@Composable
private fun Header(target: NetworkTarget?, destination: DestinationActivity?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(target?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
            if (destination != null) {
                val separator = stringResource(R.string.jail_summary_separator)
                val parts = buildList {
                    val hosts = destination.hosts.size
                    if (hosts > 1) add(pluralStringResource(R.plurals.jail_net_subdomains, hosts, hosts))
                    add(stringResource(R.string.jail_net_first_last, timeWithDateIfNotToday(destination.first), timeWithDateIfNotToday(destination.last)))
                }
                val blocked = stringResource(R.string.jail_net_blocked)
                val tertiary = MaterialTheme.colorScheme.tertiary
                Text(
                    buildAnnotatedString {
                        if (destination.rule != null) {
                            withStyle(SpanStyle(color = tertiary)) { append(blocked) }
                            append(separator)
                        }
                        append(parts.joinToString(separator))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.jail_close))
        }
    }
}

/** Blocks the domain with its subdomains, or the address on every port; or, blocked, unblocks it. */
@Composable
private fun MainAction(destination: DestinationActivity, toggle: (NetworkTarget, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val button = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
    val rule = destination.rule
    if (rule != null) {
        OutlinedButton(onClick = { toggle(rule, false) }, modifier = button) {
            Text(stringResource(R.string.jail_net_unblock))
        }
        return
    }
    val target = destination.target
    Button(
        onClick = { toggle(target, true) },
        modifier = button,
        colors = ButtonDefaults.buttonColors(),
    ) {
        Icon(Icons.Outlined.Block, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            when (target) {
                is NetworkTarget.Domain -> stringResource(R.string.jail_net_block_site, target.name)
                is NetworkTarget.Address -> stringResource(R.string.jail_net_block_address, target.name)
            },
        )
    }
}

/** A limit of blocking that bears on this destination, said plainly. */
@Composable
private fun Limit(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Data both ways and connections, then the hours it was reached in. */
@Composable
private fun Stats(destination: DestinationActivity, detail: DestinationDetail) {
    val numbers = NumberFormat.getIntegerInstance()
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            destination.sent?.let { Stat(formatBytes(it), stringResource(R.string.jail_net_sent), Modifier.weight(1f)) }
            destination.received?.let { Stat(formatBytes(it), stringResource(R.string.jail_net_received), Modifier.weight(1f)) }
            Stat(numbers.format(destination.connections), stringResource(R.string.jail_net_connections_label), Modifier.weight(1f))
        }
        if (detail.buckets.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright, modifier = Modifier.fillMaxWidth()) {
                ActivityChart(
                    buckets = detail.buckets,
                    bucketSeconds = detail.bucketSeconds,
                    zone = detail.zone,
                    selected = null,
                    onSelect = null,
                    height = 36.dp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A port, with its usual service, what went through it and, on an address, a button blocking it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PortRow(port: PortActivity, onToggle: ((Boolean) -> Unit)?, index: Int, count: Int, modifier: Modifier = Modifier) {
    val title = portService(port.port)?.let { "${port.port} · $it" } ?: port.port.toString()
    val separator = stringResource(R.string.jail_summary_separator)
    val counts = countsText(port)
    val supporting = if (port.sent != null || port.received != null) {
        counts + separator + pluralStringResource(R.plurals.jail_net_connections, port.connections, port.connections)
    } else {
        counts
    }
    ListItem(
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        supportingContent = {
            Text(
                buildAnnotatedString {
                    if (port.rule != null) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) { append(stringResource(R.string.jail_net_blocked)) }
                        append(separator)
                    }
                    append(supporting)
                },
            )
        },
        trailingContent = onToggle?.let { { BlockToggle(title, port.rule != null, it) } },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(title) }
}

/** The first few addresses, and how many more. */
@Composable
private fun Addresses(addresses: List<String>, count: Int, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        addresses.take(ADDRESSES_SHOWN).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val more = count - minOf(addresses.size, ADDRESSES_SHOWN)
        if (more > 0) {
            Text(pluralStringResource(R.plurals.jail_net_more, more, more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
