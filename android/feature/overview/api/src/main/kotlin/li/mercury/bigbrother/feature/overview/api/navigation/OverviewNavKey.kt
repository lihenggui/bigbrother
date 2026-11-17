package li.mercury.bigbrother.feature.overview.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.mercury.bigbrother.core.navigation.Navigator

/** What kinds of private data were accessed, and by how many apps. The app's start destination. */
@Serializable
data object OverviewNavKey : NavKey

/**
 * The apps that accessed one kind of private data.
 *
 * @param category the kind of data, as a data category's name.
 */
@Serializable
data class CategoryNavKey(val category: String) : NavKey

fun Navigator.navigateToCategory(category: String) {
    // Categories belong to the overview, wherever they are opened from (the
    // search bar is on every destination). A category replaces whatever was
    // open above the overview, so that back returns to it.
    if (state.currentTopLevelKey != OverviewNavKey) navigate(OverviewNavKey)
    state.currentSubStack.run { if (size > 1) subList(1, size).clear() }
    navigate(CategoryNavKey(category))
}
