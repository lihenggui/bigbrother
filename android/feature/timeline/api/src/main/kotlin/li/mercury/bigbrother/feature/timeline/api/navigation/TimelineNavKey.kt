package li.mercury.bigbrother.feature.timeline.api.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** When apps accessed private data, most recent first. */
@Serializable
data object TimelineNavKey : NavKey
