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

package li.mercury.bigbrother.feature.appdetail.impl

/**
 * What one record says beyond its row's title, shown when the row is
 * expanded.
 *
 * Records are stored under identifiers meant for the daemon, such as
 * `binder:android.content.pm.IPackageManager/getPackageInfo#com.example` or
 * `/proc/version`. The row's title already says what kind
 * of data it was ("Installed apps", "Kernel version"), so only what tells the
 * records of one row apart and means something to the user is kept: which
 * app was asked about (to count them), which command ran, which address was
 * contacted.
 */
internal sealed interface RecordDetail {
    /** Another app the app asked the system about. */
    data class App(val packageName: String) : RecordDetail

    data class Plain(val text: String) : RecordDetail
}

/** The detail of one record of a row with a title; `null` if the title says it all. */
internal fun recordDetail(subject: String): RecordDetail? = when {
    // Only calls about another app keep an argument, its package name.
    // A `!` before the name: the app was not installed when asked about.
    subject.startsWith(BINDER) ->
        subject.substringAfter('#', "").removePrefix("!").ifEmpty { null }?.let(RecordDetail::App)

    subject.startsWith(EXEC) -> RecordDetail.Plain(subject.removePrefix(EXEC))

    subject.startsWith(NET) -> RecordDetail.Plain(subject.removePrefix(NET))

    // System file paths, content URIs, settings and permission records: the
    // topic's title names what they hold.
    else -> null
}

/**
 * The details of a row's records, without repeats, in the records' order.
 * A row without a title (a topic this build does not know) has nothing
 * else to go by, so its records are listed as they are.
 */
internal fun recordDetails(subjects: List<String>, titled: Boolean): List<RecordDetail> = if (titled) {
    subjects.mapNotNull(::recordDetail).distinct()
} else {
    subjects.distinct().map(RecordDetail::Plain)
}

private const val BINDER = "binder:"
private const val EXEC = "exec:"
private const val NET = "net://"
