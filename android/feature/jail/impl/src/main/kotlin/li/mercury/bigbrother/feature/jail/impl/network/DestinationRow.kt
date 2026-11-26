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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import li.mercury.bigbrother.core.designsystem.component.segmentShape
import li.mercury.bigbrother.core.model.ConnectionCounts
import li.mercury.bigbrother.core.ui.ltrContent
import li.mercury.bigbrother.feature.jail.impl.R

/**
 * A destination in a segmented group: its name, what went to it, and the
 * button that blocks it. Blocking changes the second line in place, with a
 * cross-fade; the row does not move. [selected] is whether the pane beside
 * shows it, null without such a pane.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DestinationRow(
    row: DestinationRow,
    onOpen: (() -> Unit)?,
    onToggle: (Boolean) -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
) {
    ListItem(
        modifier = modifier
            .clip(segmentShape(index, count))
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = if (selected == true) {
            ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.segmentedColors()
        },
        leadingContent = {
            Icon(
                if (row.isAddress) Icons.Outlined.Dns else Icons.Outlined.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = {
            val line = rowLine(row)
            val enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())
            val exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
            AnimatedContent(
                targetState = line,
                transitionSpec = { enter togetherWith exit },
                label = "line",
            ) { Text(it) }
        },
        trailingContent = row.toggle?.let { { BlockToggle(row.title, row.blocked, onToggle) } },
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(row.title, style = LocalTextStyle.current.ltrContent()) }
}

/** The second line of a row: what was blocked and refused, or what went to it. */
@Composable
private fun rowLine(row: DestinationRow): AnnotatedString {
    val separator = stringResource(R.string.jail_summary_separator)
    val blockedLabel = stringResource(R.string.jail_net_blocked)
    val tertiary = MaterialTheme.colorScheme.tertiary
    val parts = buildList {
        when {
            row.blocked -> {
                if (row.manual) add(stringResource(R.string.jail_net_manual))
                if (row.noAttempts) {
                    if (row.manual) add(stringResource(R.string.jail_net_no_attempts))
                } else if (row.refused > 0) {
                    add(pluralStringResource(R.plurals.jail_net_refused, row.refused, row.refused))
                }
            }

            row.noAttempts -> add(stringResource(R.string.jail_net_no_attempts))

            else -> {
                add(countsText(row))
                row.port?.let { add(stringResource(R.string.jail_net_port, it)) }
                if (row.subdomains > 0) add(pluralStringResource(R.plurals.jail_net_subdomains, row.subdomains, row.subdomains))
            }
        }
    }
    return buildAnnotatedString {
        if (row.blocked) {
            // Said in words as well as in color.
            withStyle(SpanStyle(color = tertiary)) { append(blockedLabel) }
            parts.forEach {
                append(separator)
                append(it)
            }
        } else {
            append(parts.joinToString(separator))
        }
    }
}

/** What went to a destination: data both ways, or, where not counted, how often it was reached or refused. */
@Composable
internal fun countsText(counts: ConnectionCounts): String = when {
    counts.connections == 0 && counts.refused > 0 -> pluralStringResource(R.plurals.jail_net_refused, counts.refused, counts.refused)
    counts.sent != null || counts.received != null -> "↑ ${formatBytes(counts.sent ?: 0)} · ↓ ${formatBytes(counts.received ?: 0)}"
    else -> pluralStringResource(R.plurals.jail_net_connections, counts.connections, counts.connections)
}
