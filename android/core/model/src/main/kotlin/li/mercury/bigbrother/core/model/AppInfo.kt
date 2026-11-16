package li.mercury.bigbrother.core.model

import android.graphics.Bitmap

/** Display name and icon of an installed app. */
class AppInfo(
    val label: String,
    val icon: Bitmap?,
    /** Part of the system image, as opposed to installed by the user. */
    val isSystem: Boolean,
)
