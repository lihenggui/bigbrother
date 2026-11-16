package li.mercury.bigbrother.core.model

/** Version and install facts about an installed package, for the app info tab. */
data class PackageDetails(
    val versionName: String?,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int,
    /** Milliseconds since the epoch. */
    val firstInstallTime: Long,
    /** Milliseconds since the epoch. */
    val lastUpdateTime: Long,
)
