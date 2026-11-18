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

package li.mercury.bigbrother.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.model.AppInfo
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.SubjectCount
import li.mercury.bigbrother.core.model.clipboardCounts
import li.mercury.bigbrother.core.model.sensorsUsed

/** How many names a summary spells out before counting the rest. */
private const val NAMED_IN_SUMMARY = 2

/** "A, B and 3 more", or null for an empty list. [total] counts names not in [names] too. */
@Composable
fun namedList(names: List<String>, total: Int = names.size): String? {
    if (names.isEmpty()) return null
    val named = names.take(NAMED_IN_SUMMARY).joinToString(stringResource(R.string.summary_separator))
    val more = total - minOf(names.size, NAMED_IN_SUMMARY)
    return if (more > 0) pluralStringResource(R.plurals.summary_more, more, named, more) else named
}

/** The topic ids whose records say more than a count (see [topicRecordsSummary]). */
private const val CLIPBOARD = "clipboard"
private const val SENSORS = "sensors"

/**
 * What the records of one kind say beyond how often, for the kinds where
 * they differ: which way the clipboard went ("Read 3 times · Written once"),
 * which sensors were turned on ("Accelerometer, Gyroscope (high rate) · 12
 * times"); [compact] for a line shared with more. Null for other kinds, and
 * for records that do not say it.
 */
@Composable
fun topicRecordsSummary(topic: String?, records: List<SubjectCount>, compact: Boolean = false): String? = when (topic) {
    CLIPBOARD -> clipboardCounts(records)?.let { clipboardSummary(it, compact) }
    SENSORS -> sensorsUsed(records)?.let { sensorsSummary(it, compact) }
    else -> null
}

/** The kinds of data an app accessed, most sensitive first: "Files and media, Location and 3 more". */
@Composable
fun categoriesSummary(categories: List<DataCategory>): String? = namedList(categories.map { stringResource(it.title) })

/** Whether an app read any user file, directly or through the media library. */
val AppSummary.readFiles: Boolean
    get() = filesRead > 0 || count(Category.PROVIDER) > 0

/** What the app accessed within [category]: folders for files, item names for the rest. */
@Composable
fun categoryDetail(app: AppSummary, category: DataCategory): String? {
    if (category == DataCategory.FILES) {
        val parts = buildList {
            if (app.folders.isNotEmpty()) {
                namedList(app.folders, total = app.folderCount)?.let(::add)
            } else if (app.count(Category.STORAGE) > 0) {
                add(
                    pluralStringResource(
                        R.plurals.summary_storage,
                        app.count(Category.STORAGE),
                        app.count(Category.STORAGE),
                    ),
                )
            }
            if (app.count(Category.PROVIDER) > 0) add(stringResource(R.string.summary_media_library))
        }
        val summary = parts.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.summary_separator))
            ?: return null
        // Folders an app only listed, or was refused, are marked as such.
        return if (app.readFiles) summary else stringResource(R.string.summary_not_read, summary)
    }
    val titles = app.topics
        .filter { (topicCategory(it) ?: DataCategory.DEVICE_PROFILE) == category }
        .map { topic -> topicTitle(topic)?.let { stringResource(it) } ?: topic }
    return namedList(titles)
}

/**
 * Whether an app is left out unless system apps are shown: system apps,
 * whose reading data is how they work, and isolated processes that could not
 * be traced to an app, which are mostly system sandboxes too. An uninstalled
 * app, whose details are unknown, is not assumed to be a system app.
 */
fun isHiddenByDefault(app: AppSummary, appInfo: Map<String, AppInfo>): Boolean = appInfo[app.packageName]?.isSystem == true || app.isUnattributedIsolated

/**
 * Whether an app's records are shown: never this app's own ([ownPackage])
 * lookups, and those of apps hidden by default only with [showSystemApps].
 */
fun isShown(app: AppSummary, appInfo: Map<String, AppInfo>, showSystemApps: Boolean, ownPackage: String): Boolean = app.packageName != ownPackage && (showSystemApps || !isHiddenByDefault(app, appInfo))
