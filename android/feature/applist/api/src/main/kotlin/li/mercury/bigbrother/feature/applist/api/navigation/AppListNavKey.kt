package li.mercury.bigbrother.feature.applist.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The list of apps that have records. The app's start destination. */
@Serializable
data object AppListNavKey : NavKey
