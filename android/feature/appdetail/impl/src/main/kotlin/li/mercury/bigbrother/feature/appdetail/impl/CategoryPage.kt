package li.mercury.bigbrother.feature.appdetail.impl

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

/**
 * Records of one kind: grouped by topic (or by path, for records without
 * one) and by source, since the system's permission records and what the
 * daemon observed answer different questions.
 */
internal class TopicGroup(val topic: String, val accesses: List<Access>, val category: DataCategory) {
    /** From the system's own permission records rather than observed by the daemon. */
    val fromPermissions: Boolean get() = accesses.first().category == Category.PERMISSION

    /** Unique within the screen. */
    val key: String get() = "$topic:$fromPermissions"
}

/**
 * Every record that is not a file, grouped into its category of private data
 * in category order. Within a category, what was observed comes before the
 * permission records.
 */
internal fun groupByCategory(accesses: List<Access>): Map<DataCategory, List<TopicGroup>> =
    accesses
        .filterNot { it.isFile() }
        .groupBy { (it.topic ?: it.path) to (it.category == Category.PERMISSION) }
        .map { (key, group) ->
            val first = group.first()
            TopicGroup(key.first, group, dataCategoryOf(first.topic, first.category))
        }
        .sortedBy { it.fromPermissions }
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

/**
 * One kind of private data: what it is, where the record comes from, and how
 * often it was accessed. Tapping reveals the exact paths.
 */
@Composable
private fun LazyItemScope.TopicRow(group: TopicGroup, index: Int, count: Int) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    val title = topicTitle(group.topic)
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
        modifier = Modifier.animatedRow(this, MaterialTheme.motionScheme),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(),
        supportingContent = {
            Column {
                val allowed = group.accesses.sumOf { it.allowed }
                val denied = group.accesses.sumOf { it.denied }
                val lastSeen = group.accesses.maxOf { it.lastSeen }
                // What is known differs by how it was observed: a file was
                // read, a service was only seen being asked.
                val kind = group.accesses.map { it.action }.distinct().singleOrNull()
                val source = if (group.fromPermissions) {
                    stringResource(R.string.source_permissions) + stringResource(R.string.time_separator)
                } else {
                    ""
                }
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
                        pluralStringResource(done, allowed, allowed)
                    } + stringResource(R.string.time_separator) +
                        remember(lastSeen) { relativeTime(lastSeen) },
                    color = if (allowed == 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(motion.defaultSpatialSpec()) +
                        fadeIn(motion.defaultEffectsSpec()),
                    exit = shrinkVertically(motion.fastSpatialSpec()) +
                        fadeOut(motion.fastEffectsSpec()),
                ) {
                    Column(Modifier.padding(top = 8.dp)) {
                        for (path in group.accesses.map { it.path }.distinct()) {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
    ) {
        Text(if (title != null) stringResource(title) else group.topic)
    }
}
