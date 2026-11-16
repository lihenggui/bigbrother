package li.mercury.bigbrother.core.model

/** What kind of information a recorded path exposes. Mirrors the daemon's categories. */
enum class Category(val id: String) {
    /** User files on shared storage. */
    STORAGE("storage"),

    /** Hardware and device identifiers. */
    DEVICE("device"),

    /** System-wide kernel information. */
    SYSTEM("system"),

    /** Another app's or the system's processes. */
    PROCESS("process"),

    /**
     * Collections queried through a system content provider, such as the
     * media library. Identified by content URI rather than file path.
     */
    PROVIDER("provider");

    companion object {
        fun fromId(id: String): Category? = entries.firstOrNull { it.id == id }
    }
}

/** What an app did with a path, decoded from the daemon's mode bits. */
enum class Action {
    READ,
    WRITE,
    READ_WRITE,
    LIST,
    PROBE,
    WATCH,

    /** Listed entries of a content provider without opening them. */
    QUERY;

    companion object {
        private const val MODE_READ = 1
        private const val MODE_WRITE = 2
        private const val MODE_DIR = 4
        private const val MODE_PROBE = 8
        private const val MODE_WATCH = 16
        private const val MODE_VIA_PROVIDER = 32
        private const val MODE_QUERY = 64

        /** Whether the system opened the file on the app's behalf. */
        fun isViaProvider(mode: Int): Boolean = mode and MODE_VIA_PROVIDER != 0

        fun fromMode(mode: Int): Action = when {
            mode and MODE_QUERY != 0 -> QUERY
            mode and MODE_PROBE != 0 -> PROBE
            mode and MODE_WATCH != 0 -> WATCH
            mode and MODE_DIR != 0 -> LIST
            mode and MODE_WRITE != 0 && mode and MODE_READ != 0 -> READ_WRITE
            mode and MODE_WRITE != 0 -> WRITE
            else -> READ
        }
    }
}

/** One app's activity in the selected time range. */
data class AppSummary(
    val uid: Int,
    /** Empty for isolated processes, which have no package of their own. */
    val packageName: String,
    /** Distinct paths touched, per category. */
    val counts: Map<Category, Int>,
    val lastSeen: Long,
    /**
     * Files on shared storage it opened successfully: what it actually read,
     * as opposed to looked for or was refused.
     */
    val filesRead: Int = 0,
    /**
     * Top-level shared-storage folders of the files it read (of everything it
     * touched, if it read none), most-touched first; a short list.
     */
    val folders: List<String> = emptyList(),
    /** How many top-level folders it touched in total. */
    val folderCount: Int = 0,
    /** Kinds of device and system information it read, as topic ids. */
    val topics: List<String> = emptyList(),
) {
    /** Android user the app runs as: uid = user * 100000 + app id. */
    val userId: Int get() = uid / PER_USER_RANGE

    fun count(category: Category): Int = counts[category] ?: 0

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}

/** One path an app touched, aggregated over the selected time range. */
data class Access(
    val category: Category,
    val path: String,
    val action: Action,
    /**
     * True when a system content provider opened the file for the app, as
     * opposed to the app opening it directly.
     */
    val viaProvider: Boolean,
    /** For queries: the most entries a single query returned. */
    val items: Int,
    /** Times the system allowed it. */
    val allowed: Int,
    /** Times the system refused it. */
    val denied: Int,
    val lastSeen: Long,
    /**
     * For device and system information: an id naming what the path reveals
     * (e.g. "mac_address"). Null for user files and provider URIs.
     */
    val topic: String? = null,
)

/**
 * One entry in a path's history. Normally a single access, with [firstSeen]
 * equal to [lastSeen]; accesses within the same second are counted together.
 * Records made before individual accesses were kept cover an hour, of which
 * only the first and last time are known.
 */
data class AccessPeriod(
    val action: Action,
    val viaProvider: Boolean,
    val allowed: Int,
    val denied: Int,
    val firstSeen: Long,
    val lastSeen: Long,
)

/** Selectable time ranges. */
enum class TimeRange(val hours: Int) {
    HOUR(1),
    DAY(24),
    WEEK(24 * 7),

    /** No limit. */
    ALL(0);

    companion object {
        fun fromHours(hours: Int): TimeRange = entries.firstOrNull { it.hours == hours } ?: ALL
    }
}
