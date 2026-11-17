package li.mercury.bigbrother.feature.appdetail.impl

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.ui.DataCategory
import li.mercury.bigbrother.core.ui.Message
import li.mercury.bigbrother.core.ui.R as UiR
import li.mercury.bigbrother.core.ui.dataCategoryOf
import li.mercury.bigbrother.core.ui.label
import li.mercury.bigbrother.core.ui.relativeTime
import li.mercury.bigbrother.core.ui.topicTitle

/** Where a record comes from, in the order the rows are listed. */
internal enum class RecordSource(@StringRes val label: Int?) {
    /** Observed by the daemon, done by the app itself. */
    APP(null),

    /** Observed by the daemon, done by one of the app's isolated processes. */
    ISOLATED(R.string.source_isolated),

    /** The system's own permission records. */
    PERMISSIONS(R.string.source_permissions),
}

private val Access.source: RecordSource
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
internal class TopicGroup(
    val topic: String,
    val accesses: List<Access>,
    val category: DataCategory,
    val source: RecordSource,
) {
    /** From the system's own permission records rather than observed by the daemon. */
    val fromPermissions: Boolean get() = source == RecordSource.PERMISSIONS

    /** Unique within the screen. */
    val key: String get() = "$topic:$source"
}

/**
 * Every record that is not a file, grouped into its category of private data
 * in category order. Within a category, the app's own records come first,
 * then its isolated processes', then the permission records.
 */
internal fun groupByCategory(accesses: List<Access>): Map<DataCategory, List<TopicGroup>> =
    accesses
        .filterNot { it.isFile() }
        .groupBy { (it.topic ?: it.path) to it.source }
        .map { (key, group) ->
            val first = group.first()
            TopicGroup(key.first, group, dataCategoryOf(first.topic, first.category), key.second)
        }
        .sortedBy { it.source }
        .groupBy { it.category }
        .toSortedMap()

/** One tab per category of private data: each kind of record in it, one row each. */
@Composable
internal fun CategoryPage(groups: List<TopicGroup>, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (groups.isEmpty()) {
            item(key = "empty") {
                Message(stringResource(UiR.string.no_matches), "", Modifier.fillParentMaxHeight(0.7f))
            }
        }
        itemsIndexed(groups, key = { _, group -> group.key }) { index, group ->
            TopicRow(group, index, groups.size)
        }
    }
}

/** At most this many details are listed before the rest are asked for. */
private const val DETAILS_SHOWN = 5

/**
 * One kind of private data: what it is, where the record comes from, and how
 * often it was accessed. Tapping reveals what was accessed, when the records
 * say more than the title: which commands ran, which addresses were contacted.
 */
@Composable
private fun LazyItemScope.TopicRow(group: TopicGroup, index: Int, count: Int) {
    val title = topicTitle(group.topic)
    val details = remember(group) { recordDetails(group.accesses.map { it.path }, titled = title != null) }
    // The apps asked about are counted, not listed: an app that looks up
    // the installed apps may well go through hundreds. Counted only when
    // every record names one; a call that lists them all would make the
    // count too low.
    val appsAsked = remember(group) {
        val paths = group.accesses.map { it.path }
        if (title != null && paths.all { recordDetail(it) is RecordDetail.App }) details.size else 0
    }
    val listed = details.filterIsInstance<RecordDetail.Plain>()
    val modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme)
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    if (listed.isEmpty()) {
        ListItem(
            modifier = modifier,
            shapes = shapes,
            colors = ListItemDefaults.segmentedColors(),
            supportingContent = { TopicSummary(group, appsAsked) },
        ) {
            Text(if (title != null) stringResource(title) else group.topic)
        }
        return
    }

    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    var showAll by rememberSaveable(group.key) { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion.fastSpatialSpec(),
        label = "expand",
    )
    ListItem(
        onClick = { expanded = !expanded },
        trailingContent = {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.hide_details else R.string.show_details,
                ),
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier,
        shapes = shapes,
        colors = ListItemDefaults.segmentedColors(),
        supportingContent = {
            Column {
                TopicSummary(group, appsAsked)
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(motion.defaultSpatialSpec()) +
                        fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) +
                        fadeOut(motion.fastEffectsSpec()),
                ) {
                    Column(Modifier.padding(top = 8.dp)) {
                        val shown = if (showAll) listed else listed.take(DETAILS_SHOWN)
                        for (detail in shown) {
                            Text(
                                text = detail.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (shown.size < listed.size) {
                            TextButton(
                                onClick = { showAll = true },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(stringResource(R.string.show_all_details, listed.size))
                            }
                        }
                    }
                }
            }
        },
    ) {
        Text(if (title != null) stringResource(title) else group.topic)
    }
}

/**
 * How often the records of a row were accessed, by whom, and when last.
 * For lookups of other apps, how many apps were asked about, which says
 * more than how many calls it took.
 */
@Composable
private fun TopicSummary(group: TopicGroup, appsAsked: Int) {
    val allowed = group.accesses.sumOf { it.allowed }
    val denied = group.accesses.sumOf { it.denied }
    val lastSeen = group.accesses.maxOf { it.lastSeen }
    // What is known differs by how it was observed: a file was
    // read, a service was only seen being asked.
    val kind = group.accesses.map { it.action }.distinct().singleOrNull()
    val source = group.source.label?.let {
        stringResource(it) + stringResource(R.string.time_separator)
    }.orEmpty()
    Text(
        text = source + if (allowed == 0) {
            val blocked = if (kind == Action.EXEC) R.plurals.total_not_run else R.plurals.total_blocked
            pluralStringResource(blocked, denied, denied)
        } else {
            val done = when {
                // The system's own records say the permission was used.
                group.fromPermissions -> R.plurals.total_used
                kind == Action.CALL -> R.plurals.total_requested
                kind == Action.CONNECT -> R.plurals.total_contacted
                kind == Action.EXEC -> R.plurals.total_ran
                else -> R.plurals.total_allowed
            }
            if (appsAsked > 0) {
                pluralStringResource(R.plurals.apps_asked, appsAsked, appsAsked)
            } else {
                pluralStringResource(done, allowed, allowed)
            }
        } + stringResource(R.string.time_separator) +
            remember(lastSeen) { relativeTime(lastSeen) },
        color = if (allowed == 0) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
