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

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.SubjectCount
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.LocalShowsDetailBesideList
import li.mercury.bigbrother.core.ui.dataCategoryOf
import li.mercury.bigbrother.core.ui.shortTime
import li.mercury.bigbrother.core.ui.topicIcon
import li.mercury.bigbrother.core.ui.topicRecordsSummary
import li.mercury.bigbrother.core.ui.topicTitle

/** Where a record comes from, in the order the rows are listed. */
enum class RecordSource(@StringRes val label: Int?) {
    /** Observed by the daemon, done by the app itself. */
    APP(null),

    /** Observed by the daemon, done by one of the app's isolated processes. */
    ISOLATED(R.string.source_isolated),

    /** The system's own permission records. */
    PERMISSIONS(R.string.source_permissions),
}

internal val Access.source: RecordSource
    get() = when {
        category == Category.PERMISSION -> RecordSource.PERMISSIONS
        fromIsolated -> RecordSource.ISOLATED
        else -> RecordSource.APP
    }

/**
 * Records of one kind: grouped by topic (or by path, for records without
 * one) and by source, since the app itself, its sandboxed processes and the
 * system's permission records answer different questions.
 */
class TopicGroup(
    val topic: String,
    val accesses: List<Access>,
    val category: DataCategory,
    val source: RecordSource,
) {
    /** From the system's own permission records rather than observed by the daemon. */
    val fromPermissions: Boolean get() = source == RecordSource.PERMISSIONS

    /** Unique within the screen. */
    val key: String get() = "$topic:$source"

    val allowed: Int get() = accesses.sumOf { it.allowed }
    val denied: Int get() = accesses.sumOf { it.denied }
    val lastSeen: Long get() = accesses.maxOf { it.lastSeen }

    /** Each record with its counts. */
    val records: List<SubjectCount> get() = accesses.map { SubjectCount(it.path, it.allowed, it.denied) }
}

/**
 * Every record that is not a file, grouped into its category of private data
 * in category order. Within a category, the app's own records come first,
 * then its isolated processes', then the permission records.
 */
internal fun groupByCategory(accesses: List<Access>): Map<DataCategory, List<TopicGroup>> = accesses
    .filterNot { it.isFile() }
    .groupBy { (it.topic ?: it.path) to it.source }
    .map { (key, group) ->
        val first = group.first()
        TopicGroup(key.first, group, dataCategoryOf(first.topic, first.category), key.second)
    }
    .sortedBy { it.source }
    .groupBy { it.category }
    .toSortedMap()

/** The topic of looking for root, among other ways by the apps that need it. */
internal const val ROOT_CHECK = "root_check"

/** The topic of asking about apps that are not installed. */
internal const val APP_PROBE = "app_probe"

/**
 * How many other apps a group's records asked the system about, or 0 when
 * that is not what they are. An app that looks up installed apps may go
 * through hundreds, so they are counted, not listed. Counted only when every
 * record names one; a call that lists them all would make the count too low.
 */
internal fun appsAsked(group: TopicGroup): Int {
    val titled = topicTitle(group.topic) != null
    val details = recordDetails(group.accesses.map { it.path }, titled)
    val apps = details.count { it is RecordDetail.App }
    return when {
        // Every root app looked for is in it; other records there, such
        // as commands run, are listed separately.
        group.topic == ROOT_CHECK || group.topic == APP_PROBE -> apps

        titled && group.accesses.all { recordDetail(it.path) is RecordDetail.App } -> apps

        else -> 0
    }
}

/**
 * One kind of private data: what it is, where the record comes from, and how
 * often it was accessed. Tapping opens what exactly was accessed and when.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TopicRow(
    group: TopicGroup,
    index: Int,
    count: Int,
    selected: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = topicTitle(group.topic)
    ListItem(
        selected = selected && LocalShowsDetailBesideList.current,
        onClick = onOpen,
        modifier = modifier,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = {
            Icon(topicIcon(group.topic.takeIf { title != null }), contentDescription = null)
        },
        supportingContent = { TopicSummary(group) },
        trailingContent = { Text(shortTime(group.lastSeen), style = MaterialTheme.typography.labelMedium) },
    ) {
        Text(
            text = if (title != null) stringResource(title) else group.topic,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * How often the records of a row were accessed, and by whom. For lookups of
 * other apps, how many apps were asked about, which says more than how many
 * calls it took; for the clipboard, which way it went; for sensors, which
 * ones. An access the system always refused is marked as such, but not as
 * an error: the refusal protected the data.
 */
@Composable
internal fun TopicSummary(group: TopicGroup) {
    val asked = remember(group) { appsAsked(group) }
    val source = group.source.label?.let {
        stringResource(it) + stringResource(R.string.time_separator)
    }.orEmpty()
    val allowed = group.allowed
    val detail = topicRecordsSummary(group.topic, remember(group) { group.records })
    if (allowed == 0 && detail != null) {
        DeniedText(source + detail)
        return
    }
    if (allowed == 0) {
        val kind = group.accesses.map { it.action }.distinct().singleOrNull()
        val blocked = if (kind == Action.EXEC) R.plurals.total_not_run else R.plurals.total_blocked
        DeniedText(source + pluralStringResource(blocked, group.denied, group.denied))
        return
    }
    // The clipboard's directions and the sensors' names may take a second line.
    Text(source + (detail ?: outcome(group, allowed, asked)), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** "Read 3 times", "Looked up 12 apps" and the like. */
@Composable
internal fun outcome(group: TopicGroup, allowed: Int, asked: Int): String {
    topicRecordsSummary(group.topic, remember(group) { group.records })?.let { return it }
    if (asked > 0) {
        val plural = when (group.topic) {
            ROOT_CHECK -> R.plurals.root_apps_asked
            APP_PROBE -> R.plurals.absent_apps_asked
            else -> R.plurals.apps_asked
        }
        return pluralStringResource(plural, asked, asked)
    }
    // What is known differs by how it was observed: a file was read, a
    // service was only seen being asked.
    val kind = group.accesses.map { it.action }.distinct().singleOrNull()
    val done = when {
        // The system's own records say the permission was used.
        group.fromPermissions -> R.plurals.total_used

        kind == Action.CALL -> R.plurals.total_requested

        kind == Action.CONNECT -> R.plurals.total_contacted

        kind == Action.EXEC -> R.plurals.total_ran

        else -> R.plurals.total_allowed
    }
    return pluralStringResource(done, allowed, allowed)
}

/** Text about a refused access, with a "blocked" icon so it is not told apart by color alone. */
@Composable
internal fun DeniedText(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Block,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
