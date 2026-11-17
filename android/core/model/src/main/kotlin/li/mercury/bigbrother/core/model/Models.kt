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
    PROVIDER("provider"),

    /**
     * Addresses on this device and the local network the app contacted.
     * Identified as `net://<address>:<port>`.
     */
    NETWORK("network"),

    /** Programs the app started. Identified as `exec:<command line>`. */
    COMMAND("command"),

    /**
     * Personal data kept by the system: contacts, messages, the call log,
     * the calendar. Identified by the collection's content URI.
     */
    PERSONAL("personal"),

    /**
     * What the app asked system services for: installed apps, the clipboard,
     * the location and so on. Identified as `binder:<interface>/<method>`.
     */
    SERVICE("service"),

    /**
     * Uses of sensitive permissions as the system itself recorded them:
     * camera, microphone, location and so on. Identified as `appop:<name>`.
     */
    PERMISSION("permission"),
    ;

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
    QUERY,

    /** Tried to reach a network address. */
    CONNECT,

    /** Started a program. */
    EXEC,

    /** Asked a system service for something; what it was given is not known. */
    CALL,

    ;

    companion object {
        private const val MODE_READ = 1
        private const val MODE_WRITE = 2
        private const val MODE_DIR = 4
        private const val MODE_PROBE = 8
        private const val MODE_WATCH = 16
        private const val MODE_VIA_PROVIDER = 32
        private const val MODE_QUERY = 64
        private const val MODE_CONNECT = 128
        private const val MODE_EXEC = 256
        private const val MODE_CALL = 512
        private const val MODE_ISOLATED = 1024

        /** Whether the system opened the file on the app's behalf. */
        fun isViaProvider(mode: Int): Boolean = mode and MODE_VIA_PROVIDER != 0

        /** Whether entries of a content provider were listed. */
        fun isQuery(mode: Int): Boolean = mode and MODE_QUERY != 0

        /**
         * Whether [modes], the modes of several accesses combined, hold
         * provider queries and nothing else, whichever process made them.
         */
        fun onlyQueries(modes: Int): Boolean = modes and MODE_ISOLATED.inv() == MODE_QUERY

        /** Whether one of the app's isolated processes did it, rather than the app itself. */
        fun isFromIsolated(mode: Int): Boolean = mode and MODE_ISOLATED != 0

        fun fromMode(mode: Int): Action = when {
            mode and MODE_EXEC != 0 -> EXEC
            mode and MODE_CALL != 0 -> CALL
            mode and MODE_CONNECT != 0 -> CONNECT
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
    /** Kinds of information it went after besides user files, as topic ids. */
    val topics: List<String> = emptyList(),
) {
    /** Android user the app runs as: uid = user * 100000 + app id. */
    val userId: Int get() = uid / PER_USER_RANGE

    /**
     * An isolated process the daemon could not trace to its app: one it did
     * trace is recorded under the app's own uid.
     */
    val isUnattributedIsolated: Boolean
        get() = packageName.isEmpty() && uid % PER_USER_RANGE >= FIRST_ISOLATED_APP_ID

    fun count(category: Category): Int = counts[category] ?: 0

    private companion object {
        const val PER_USER_RANGE = 100_000

        /** Isolated processes, with or without an app zygote, have app ids from here up. */
        const val FIRST_ISOLATED_APP_ID = 90_000
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
     * For everything but user files: an id naming what the record reveals
     * (e.g. "mac_address"). Null for user files and provider URIs.
     */
    val topic: String? = null,
    /**
     * Done by one of the app's isolated processes (a sandboxed renderer or
     * the like) rather than by the app itself.
     */
    val fromIsolated: Boolean = false,
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
    /** The record it is of, where the history of several was read one by one; null otherwise. */
    val subject: String? = null,
)

/**
 * One app's activity of one kind within one hour: a line of the timeline.
 * A kind is a [category] and, for everything but user files, a [topic].
 */
data class TimelineEntry(
    /** Start of the hour, as Unix time. */
    val hourStart: Long,
    val uid: Int,
    /** Empty for isolated processes, which have no package of their own. */
    val packageName: String,
    val category: Category,
    /** What the records mean (e.g. "mac_address"); null for user files and provider URIs. */
    val topic: String?,
    /** Distinct paths touched. */
    val paths: Int,
    /** Distinct user files opened successfully; 0 for other categories. */
    val filesRead: Int,
    val allowed: Int,
    val denied: Int,
    /** The daemon's mode bits of every access, combined. */
    val modes: Int,
    /** For provider queries: the most entries a single query returned. */
    val items: Int,
    /** For user files: the top-level folder most of them are in. */
    val folder: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    /**
     * Each record of the entry, for the kinds whose records differ in what
     * they say (the clipboard read or written, which sensor). Empty for the
     * other kinds, and from a daemon that does not report them.
     */
    val subjects: List<SubjectCount> = emptyList(),
) {
    /** Whether the only file accesses were listings, probes or watches: nothing was read. */
    val onlyLookedForFiles: Boolean get() = category == Category.STORAGE && filesRead == 0

    /** Whether everything recorded was done through a content provider query. */
    val isQuery: Boolean get() = Action.onlyQueries(modes)

    /** Whether files were opened through a content provider. */
    val viaProvider: Boolean get() = Action.isViaProvider(modes)
}

/** One record and how often it happened: a subject as the daemon stores it, with its counts. */
data class SubjectCount(
    /** What was recorded, e.g. `binder:android.content.IClipboard/getPrimaryClip`. */
    val path: String,
    /** Times the system allowed it. */
    val allowed: Int,
    /** Times the system refused it. */
    val denied: Int,
)

/**
 * One period in which an app had a sensor turned on: from when it turned
 * the sensor on until it turned it off, closed the connection or ended.
 */
data class SensorSession(
    /**
     * The sensor's type as the system names it, such as
     * `android.sensor.accelerometer`; empty if it could not be identified.
     */
    val sensor: String,
    /** Whether the app asked for 200 events a second or more. */
    val highRate: Boolean,
    /**
     * The time between events the app asked for, in nanoseconds; 0 for as
     * fast as the sensor goes. Null for sensors that report on change or
     * once, for which a rate means nothing.
     */
    val periodNs: Long?,
    /** The rate the app asked for; null when [periodNs] is null or 0. */
    val rateHz: Double?,
    /** Unix time. */
    val start: Long,
    /** Unix time; null while the sensor is still on. */
    val end: Long?,
)

/** A page of the timeline, most recent first. */
data class TimelinePage(
    val entries: List<TimelineEntry>,
    /** Where the next, older, page starts; null when there is no more. */
    val nextBefore: Long?,
)

/** A path that matched a search, with the app that touched it. */
data class PathMatch(
    val uid: Int,
    val packageName: String,
    val access: Access,
)

/** What the daemon is doing. */
data class RecorderStatus(
    /** Whether it is running and writing records. */
    val recording: Boolean,
    /**
     * Ids of the kinds of activity it cannot record on this device (e.g.
     * "network"). Nothing recorded of such a kind means nothing was looked
     * for, not that nothing happened.
     */
    val unavailable: List<String> = emptyList(),
)

/** Selectable time ranges. */
enum class TimeRange(val hours: Int) {
    HOUR(1),
    DAY(24),
    WEEK(24 * 7),

    /** No limit. */
    ALL(0),
    ;

    companion object {
        /** The range of [hours], or the default range for a value it does not know. */
        fun fromHours(hours: Int): TimeRange = entries.firstOrNull { it.hours == hours } ?: DAY
    }
}
