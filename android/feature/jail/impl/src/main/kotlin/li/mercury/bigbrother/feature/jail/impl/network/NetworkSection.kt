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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.designsystem.component.ShapedIcon
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import li.mercury.bigbrother.core.model.SandboxedApp
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.feature.jail.impl.ItemRow
import li.mercury.bigbrother.feature.jail.impl.LinkRow
import li.mercury.bigbrother.feature.jail.impl.Note
import li.mercury.bigbrother.feature.jail.impl.R
import li.mercury.bigbrother.feature.jail.impl.segmentShape

/**
 * The daemon's mode of the network for access [allowed]. The daemon has the
 * network on when the app is kept from it; the screens say whether access
 * is allowed. This and [allowsNetwork] are the only places that turn one
 * into the other.
 */
internal fun networkMode(allowed: Boolean): SandboxMode = if (allowed) SandboxMode.OFF else SandboxMode.ON

/** Whether this mode of the network lets the app connect. */
internal val SandboxMode.allowsNetwork: Boolean get() = !isOn

/** What the network section of an app's page can do. */
internal class NetworkSectionActions(
    val onExpandedChange: (Boolean) -> Unit = {},
    val onSetAllowed: (Boolean) -> Unit = {},
    val onChoose: (SandboxMode?) -> Unit = {},
    val onSetRecording: (Boolean) -> Unit = {},
    val onOpenActivity: () -> Unit = {},
)

/**
 * The network section of an app's page: whether it may connect, whether
 * its connections are recorded, and, while they are or once something is
 * recorded or blocked, the way to its network activity. An app that does
 * not ask for network access gets a line saying so instead.
 *
 * [requested] is null when not known; [destinations] how many destinations
 * it reached in the last 24 hours, null if not read.
 */
internal fun LazyListScope.networkSection(
    app: SandboxedApp,
    globalMode: SandboxMode,
    requested: Set<String>?,
    destinations: Int?,
    expanded: Boolean,
    actions: NetworkSectionActions,
) {
    item(key = "network_header") { SectionHeader(stringResource(R.string.jail_section_network)) }
    if (requested != null && !SandboxItem.NETWORK.concerns(requested)) {
        item(key = "network_none") { NoPermissionRow() }
        return
    }
    val recording = app.modeOf(SandboxItem.NETWORK_RECORD).isOn
    val activityShown = recording || app.networkRules > 0 || app.recordingSince != null
    val count = if (activityShown) 3 else 2
    item(key = SandboxItem.NETWORK.id) {
        val mode = app.modeOf(SandboxItem.NETWORK)
        ItemRow(
            item = SandboxItem.NETWORK,
            mode = mode,
            choice = app.settings[SandboxItem.NETWORK],
            globalMode = globalMode,
            unmet = app.unmet[SandboxItem.NETWORK].orEmpty(),
            expanded = expanded,
            onExpandedChange = actions.onExpandedChange,
            onCheckedChange = actions.onSetAllowed,
            onChoose = actions.onChoose,
            index = 0,
            count = count,
            checked = mode.allowsNetwork,
        )
    }
    item(key = SandboxItem.NETWORK_RECORD.id) {
        RecordRow(recording, app.recordingSince, actions.onSetRecording, index = 1, count = count)
    }
    // Always an item, so the row can grow in and out as recording is switched.
    item(key = "network_activity") {
        AnimatedVisibility(
            visible = activityShown,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            val found = destinations ?: 0
            val separator = stringResource(R.string.jail_summary_separator)
            val summary = listOfNotNull(
                if (found == 0) {
                    stringResource(R.string.jail_net_empty_title)
                } else {
                    pluralStringResource(R.plurals.jail_network_activity_summary, found, found)
                },
                pluralStringResource(R.plurals.jail_network_blocked_count, app.networkRules, app.networkRules).takeIf { app.networkRules > 0 },
            ).joinToString(separator)
            LinkRow(
                title = stringResource(R.string.jail_network_activity),
                supporting = summary,
                onClick = actions.onOpenActivity,
                index = 2,
                count = 3,
                leading = { ShapedIcon(Icons.Outlined.BarChart) },
            )
        }
    }
}

/** The network section of the global settings: access for apps without their own setting. */
internal fun LazyListScope.globalNetworkSection(
    mode: SandboxMode,
    unmet: Set<SandboxPart>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSetAllowed: (Boolean) -> Unit,
    onChoose: (SandboxMode) -> Unit,
) {
    item(key = "network_header") { SectionHeader(stringResource(R.string.jail_section_network)) }
    item(key = SandboxItem.NETWORK.id) {
        ItemRow(
            item = SandboxItem.NETWORK,
            mode = mode,
            choice = mode,
            globalMode = null,
            unmet = unmet,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            onCheckedChange = onSetAllowed,
            onChoose = { chosen -> if (chosen != null) onChoose(chosen) },
            index = 0,
            count = 1,
            checked = mode.allowsNetwork,
        )
    }
    item(key = "network_note") { Note(stringResource(R.string.jail_network_global_note), Modifier.padding(top = 8.dp)) }
}

/**
 * Whether the app's connections are recorded: what is recorded and for how
 * long, or since when. The whole row toggles it, at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecordRow(recording: Boolean, since: Long?, onCheckedChange: (Boolean) -> Unit, index: Int, count: Int) {
    ListItem(
        modifier = Modifier
            .clip(segmentShape(index, count))
            .toggleable(value = recording, role = Role.Switch, onValueChange = onCheckedChange),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { ShapedIcon(Icons.Outlined.FiberManualRecord) },
        supportingContent = {
            Text(
                if (recording && since != null) {
                    stringResource(R.string.jail_network_recording_since, dateTime(since))
                } else {
                    stringResource(R.string.jail_network_record_summary)
                },
            )
        },
        trailingContent = {
            Switch(
                checked = recording,
                onCheckedChange = null,
                thumbContent = {
                    Icon(
                        if (recording) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(stringResource(R.string.jail_network_record)) }
}

/** The only line of the section for an app that cannot connect anyway. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoPermissionRow() {
    ListItem(
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { ShapedIcon(Icons.Outlined.Public) },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(stringResource(R.string.jail_network_no_permission)) }
}
