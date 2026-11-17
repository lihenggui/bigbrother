package li.mercury.bigbrother.feature.appdetail.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.navigation.Navigator

/**
 * Everything one app touched, by kind of data.
 *
 * @param uid the app's uid, which also identifies the Android user it runs as.
 * @param packageName the app's package, empty for an isolated process.
 * @param rangeHours the time range to show, as [TimeRange.hours].
 * @param category the data category to scroll to, as its name, or null to
 *   start at the top.
 */
@Serializable
data class AppDetailNavKey(
    val uid: Int,
    val packageName: String,
    val rangeHours: Int,
    val category: String? = null,
) : NavKey

/**
 * One app's files, browsed as folders.
 *
 * @param location the folder to open, as its full path, or null for the top.
 */
@Serializable
data class AppFilesNavKey(
    val uid: Int,
    val packageName: String,
    val rangeHours: Int,
    val location: String? = null,
) : NavKey

/**
 * One kind of data an app accessed: what exactly, and each time.
 *
 * @param topic the topic id, or the path for records without a topic.
 * @param source where the records come from, as a record source's name.
 */
@Serializable
data class RecordNavKey(
    val uid: Int,
    val packageName: String,
    val rangeHours: Int,
    val topic: String,
    val source: String,
) : NavKey

fun Navigator.navigateToAppDetail(uid: Int, packageName: String, range: TimeRange, category: String? = null) {
    // Only one app is open at a time. Without this, picking app after app in
    // the two-pane layout would stack their screens.
    state.currentSubStack.removeAll { it is AppDetailNavKey || it is AppFilesNavKey || it is RecordNavKey }
    navigate(AppDetailNavKey(uid, packageName, range.hours, category))
}

fun Navigator.navigateToAppFiles(uid: Int, packageName: String, range: TimeRange, location: String? = null) {
    state.currentSubStack.removeAll { it is AppFilesNavKey || it is RecordNavKey }
    navigate(AppFilesNavKey(uid, packageName, range.hours, location))
}

fun Navigator.navigateToRecord(uid: Int, packageName: String, range: TimeRange, topic: String, source: String) {
    // The files and a record share the pane beside the app.
    state.currentSubStack.removeAll { it is AppFilesNavKey || it is RecordNavKey }
    navigate(RecordNavKey(uid, packageName, range.hours, topic, source))
}
