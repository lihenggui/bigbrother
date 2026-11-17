package li.mercury.bigbrother.core.model

/** The user's settings, kept across launches. */
data class UserData(
    /** The period the overview and the app list cover. */
    val timeRange: TimeRange = TimeRange.DAY,
    /**
     * Whether apps that are part of the system image are listed. Off by
     * default: system components read shared storage routinely and would
     * dominate every list.
     */
    val showSystemApps: Boolean = false,
    /** Whether colors follow the wallpaper rather than the app's own scheme. */
    val useDynamicColor: Boolean = true,
)
