package li.mercury.bigbrother.feature.appdetail.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.navigation.Navigator

/**
 * Everything one app touched.
 *
 * @param uid the app's uid, which also identifies the Android user it runs as.
 * @param packageName the app's package, empty for an isolated process.
 * @param rangeHours the time range to show, as [TimeRange.hours].
 */
@Serializable
data class AppDetailNavKey(val uid: Int, val packageName: String, val rangeHours: Int) : NavKey

fun Navigator.navigateToAppDetail(uid: Int, packageName: String, range: TimeRange) {
    // Only one app's detail is open at a time. Without this, picking app
    // after app in the two-pane layout would stack their screens.
    state.currentSubStack.removeAll { it is AppDetailNavKey }
    navigate(AppDetailNavKey(uid, packageName, range.hours))
}
