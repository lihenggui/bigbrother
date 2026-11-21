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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.designsystem.component.BigBrotherLoadingIndicator
import li.mercury.bigbrother.core.ui.LoadFailureMessage
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.SectionHeader
import li.mercury.bigbrother.core.ui.TimeRangeButtonGroup
import li.mercury.bigbrother.feature.jail.impl.Note
import li.mercury.bigbrother.feature.jail.impl.R

@Composable
internal fun NetworkActivityList(
    state: NetworkActivityUiState,
    selectedTarget: String?,
    padding: PaddingValues,
    actions: NetworkActivityActions,
    onDialog: (NetworkDialog) -> Unit,
) {
    val beside = LocalShowsDetailBesideList.current
    if (state.failure != null) {
        LoadFailureMessage(state.failure, onRetry = actions.onRefresh, modifier = Modifier.padding(padding))
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            activityContent(state, selectedTarget, beside, actions, onDialog = onDialog)
        }
    }
}

private fun LazyListScope.activityContent(
    state: NetworkActivityUiState,
    selectedTarget: String?,
    beside: Boolean,
    actions: NetworkActivityActions,
    onDialog: (NetworkDialog) -> Unit,
) {
    val activity = state.activity
    if (activity != null) notices(activity, actions)
    item(key = "range") {
        TimeRangeButtonGroup(
            range = state.range,
            onRange = actions.onRange,
            options = ACTIVITY_RANGES,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    if (activity == null) {
        item(key = "loading") { BigBrotherLoadingIndicator(Modifier.fillMaxWidth().padding(vertical = 96.dp)) }
        return
    }
    val nothing = activity.domains.isEmpty() && activity.addresses.isEmpty() && activity.rules.isEmpty() &&
        activity.buckets.all { it.connections + it.refused == 0 }
    if (nothing && activity.recording.on) {
        item(key = "empty") { Empty(activity.recording.since ?: activity.since) }
        return
    }
    item(key = "summary") { Summary(state, activity, actions.onSelectBucket) }
    item(key = "filters") { Filters(state, actions) }
    val shown = state.shown
    if (shown.domainCount > 0) {
        item(key = "domains") { SectionHeader(stringResource(R.string.jail_net_section_domains), trailing = shown.domainCount.toString()) }
        rows(shown.domains, selectedTarget, beside, actions)
        if (shown.domainCount > shown.domains.size) {
            item(key = "domains_all") { ShowAll(shown.domainCount, actions.onShowAllDomains) }
        }
    }
    if (shown.addressCount > 0) {
        item(key = "addresses") { SectionHeader(stringResource(R.string.jail_net_section_addresses), trailing = shown.addressCount.toString()) }
        if (!state.blockedOnly) item(key = "addresses_note") { Note(stringResource(R.string.jail_net_addresses_note)) }
        rows(shown.addresses, selectedTarget, beside, actions)
        if (shown.addressCount > shown.addresses.size) {
            item(key = "addresses_all") { ShowAll(shown.addressCount, actions.onShowAllAddresses) }
        }
    }
    if (shown.domainCount + shown.addressCount == 0 && (state.query.isNotBlank() || state.blockedOnly || state.bucket != null)) {
        item(key = "no_results") {
            Text(
                stringResource(R.string.jail_net_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
        }
    }
    if (state.blockedOnly) {
        item(key = "add") {
            TextButton(onClick = { onDialog(NetworkDialog.BLOCK) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.jail_net_menu_block))
            }
        }
    } else if (state.bucket == null) {
        item(key = "footer") { Note(stringResource(R.string.jail_net_footer), Modifier.padding(top = 8.dp)) }
    }
}

private fun LazyListScope.rows(
    rows: List<DestinationRow>,
    selectedTarget: String?,
    beside: Boolean,
    actions: NetworkActivityActions,
) {
    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
        val opens = row.opens
        DestinationRow(
            row = row,
            onOpen = opens?.let { { actions.onOpen(it) } },
            onToggle = { block ->
                val target = row.toggle ?: return@DestinationRow
                if (block) actions.onBlock(target, false) else actions.onUnblock(target)
            },
            index = index,
            count = rows.size,
            selected = if (beside) opens != null && opens.id == selectedTarget else null,
            modifier = Modifier.animateItem(),
        )
    }
}
