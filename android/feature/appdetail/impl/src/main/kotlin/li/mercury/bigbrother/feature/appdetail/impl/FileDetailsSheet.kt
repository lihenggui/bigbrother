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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.ui.exactDateTime
import li.mercury.bigbrother.core.ui.failureReason
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.ltrContent

/** When one file was accessed: a line per access, most recent first. Shown once the history has loaded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileDetailsSheet(details: FileDetails, onOpen: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = details.path.substringAfterLast('/'),
                style = MaterialTheme.typography.titleLarge.ltrContent(),
            )
            SelectionContainer {
                Text(
                    text = details.path,
                    style = MaterialTheme.typography.bodySmall.ltrContent(),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val periods = details.periods.orEmpty()
            if (details.failure != null) {
                Text(text = failureReason(details.failure), color = MaterialTheme.colorScheme.error)
            } else {
                val keys = remember(periods) { periodKeys(periods) }
                LazyColumn(
                    // Leaves room for the button below on a long history.
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(periods, key = { index, _ -> keys[index] }) { _, period -> PeriodRow(period) }
                }
            }
            // A folder that was only listed has nothing to show in a viewer.
            val viewable = periods.none { it.action == Action.LIST }
            if (viewable) {
                FilledTonalButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_file))
                }
            }
        }
    }
}

@Composable
private fun PeriodRow(period: AccessPeriod) {
    val label = actionLabel(period.action, period.viaProvider)
    Column {
        Text(text = exactDateTime(period.time), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = when {
                period.allowed == 0 -> stringResource(R.string.access_blocked, label, period.denied)

                period.denied > 0 -> stringResource(
                    R.string.access_partly_blocked,
                    label,
                    period.allowed + period.denied,
                    period.denied,
                )

                else -> stringResource(R.string.access_allowed, label, period.allowed)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
