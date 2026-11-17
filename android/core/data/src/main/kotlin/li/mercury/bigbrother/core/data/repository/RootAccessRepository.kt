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

package li.mercury.bigbrother.core.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.shellQuoted
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.PathMatch
import li.mercury.bigbrother.core.model.RecorderStatus
import li.mercury.bigbrother.core.model.TimeRange
import li.mercury.bigbrother.core.model.TimelineEntry
import li.mercury.bigbrother.core.model.TimelinePage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads records by running the daemon's query subcommands as root. The
 * daemon owns the database; its CLI prints JSON, which is the contract
 * between the two.
 */
@Singleton
internal class RootAccessRepository @Inject constructor(
    private val rootShell: RootShell,
) : AccessRepository {

    private val _recorderStatus = MutableStateFlow<RecorderStatus?>(null)
    override val recorderStatus: StateFlow<RecorderStatus?> = _recorderStatus.asStateFlow()

    /**
     * The overview, the app list, a category and an app's page each start
     * from the list of apps; one read serves them all within a flush of the
     * daemon, which writes every 5 s.
     */
    private val recentApps = RecentRead<TimeRange, List<AppSummary>>(maxAgeMs = 5_000)

    override suspend fun getApps(range: TimeRange): List<AppSummary> = recentApps.get(range) { readApps(range) }

    private suspend fun readApps(range: TimeRange): List<AppSummary> = parsing {
        val rows = JSONArray(query("apps" + range.argument()))
        List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            AppSummary(
                uid = row.getInt("uid"),
                packageName = row.getString("pkg"),
                counts = Category.entries.associateWith { row.optInt(it.id) },
                lastSeen = row.getLong("last_ts"),
                // A module older than this app doesn't report files read
                // separately; fall back to everything it touched rather than
                // show every app as having read nothing.
                filesRead = if (row.has("files_read")) row.getInt("files_read") else row.optInt("storage"),
                folders = row.optJSONArray("folders").toStringList(),
                folderCount = row.optInt("folder_count"),
                topics = row.optJSONArray("topics").toStringList(),
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }

    /** An app's page, its files and its records each read all of its records within moments; they share one read. */
    private val recentAccesses = RecentRead<List<Any?>, List<Access>>(maxAgeMs = 10_000)

    override suspend fun getAccesses(
        uid: Int,
        category: Category?,
        range: TimeRange,
        limit: Int,
        offset: Int,
    ): List<Access> = recentAccesses.get(listOf(uid, category, range, limit, offset)) {
        readAccesses(uid, category, range, limit, offset)
    }

    private suspend fun readAccesses(
        uid: Int,
        category: Category?,
        range: TimeRange,
        limit: Int,
        offset: Int,
    ): List<Access> = parsing {
        // Every interpolated value is a number or an enum constant, so none of
        // them can alter the shell command.
        val args = buildString {
            append("files --uid ").append(uid)
            append(" --limit ").append(limit)
            append(" --offset ").append(offset)
            if (category != null) append(" --cat ").append(category.id)
            append(range.argument())
        }
        val rows = JSONArray(query(args))
        (0 until rows.length()).mapNotNull { i -> rows.getJSONObject(i).toAccess() }
    }

    override suspend fun searchPaths(
        text: String,
        categories: Collection<Category>,
        uids: Collection<Int>,
        range: TimeRange,
        limit: Int,
    ): List<PathMatch> = parsing {
        // No app means nothing to search, not every app.
        if (uids.isEmpty()) return@parsing emptyList()
        // The text is typed by the user, so it is quoted: it must reach the
        // daemon as one argument, whatever it holds. Attached with "=", it
        // stays the option's value even when it starts with "-". The rest
        // are numbers and enum constants.
        val filters = categories.joinToString("") { " --cat ${it.id}" } + uids.joinToString("") { " --uid $it" }
        val rows = JSONArray(
            query("files --contains=${text.shellQuoted()}$filters --limit $limit" + range.argument()),
        )
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            val access = row.toAccess() ?: return@mapNotNull null
            PathMatch(uid = row.getInt("uid"), packageName = row.getString("pkg"), access = access)
        }
    }

    override suspend fun getTimeline(range: TimeRange, before: Long?, limit: Int): TimelinePage = parsing {
        val args = buildString {
            append("timeline --limit ").append(limit)
            if (before != null) append(" --before ").append(before)
            append(range.argument())
        }
        val page = JSONObject(query(args))
        val rows = page.getJSONArray("entries")
        TimelinePage(
            entries = (0 until rows.length()).mapNotNull { i ->
                val row = rows.getJSONObject(i)
                // A category this build doesn't know is skipped rather than guessed at.
                val category = Category.fromId(row.getString("cat")) ?: return@mapNotNull null
                TimelineEntry(
                    hourStart = row.getLong("hour_ts"),
                    uid = row.getInt("uid"),
                    packageName = row.getString("pkg"),
                    category = category,
                    topic = row.optString("topic").ifEmpty { null },
                    paths = row.getInt("paths"),
                    filesRead = row.getInt("files_read"),
                    allowed = row.getInt("ok"),
                    denied = row.getInt("fail"),
                    modes = row.getInt("modes"),
                    items = row.optInt("items"),
                    folder = row.optString("folder").ifEmpty { null },
                    firstSeen = row.getLong("first_ts"),
                    lastSeen = row.getLong("last_ts"),
                )
            },
            nextBefore = if (page.has("next_before")) page.getLong("next_before") else null,
        )
    }

    /** A row of `bbd files`; null for a category this build doesn't know, which is skipped rather than guessed at. */
    private fun JSONObject.toAccess(): Access? {
        val rowCategory = Category.fromId(getString("cat")) ?: return null
        val mode = getInt("mode")
        return Access(
            category = rowCategory,
            path = getString("path"),
            action = Action.fromMode(mode),
            viaProvider = Action.isViaProvider(mode),
            items = optInt("items"),
            allowed = getInt("ok"),
            denied = getInt("fail"),
            lastSeen = getLong("last_ts"),
            topic = optString("topic").ifEmpty { null },
            fromIsolated = Action.isFromIsolated(mode),
        )
    }

    override suspend fun getHistory(
        uid: Int,
        paths: List<String>,
        range: TimeRange,
    ): List<AccessPeriod> = parsing {
        // A path comes from a file name chosen by some app, so each is
        // quoted: it must reach the daemon as one argument, whatever it holds.
        val args = paths.joinToString("") { " --path=${it.shellQuoted()}" }
        val rows = JSONArray(query("history --uid $uid$args" + range.argument()))
        List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            val mode = row.getInt("mode")
            AccessPeriod(
                action = Action.fromMode(mode),
                viaProvider = Action.isViaProvider(mode),
                allowed = row.getInt("ok"),
                denied = row.getInt("fail"),
                firstSeen = row.getLong("first_ts"),
                lastSeen = row.getLong("last_ts"),
            )
        }
    }

    override suspend fun getRecorderStatus(): RecorderStatus = try {
        readRecorderStatus().also { _recorderStatus.value = it }
    } catch (e: RecordsException) {
        _recorderStatus.value = null
        throw e
    }

    private suspend fun readRecorderStatus(): RecorderStatus = parsing {
        val meta = JSONObject(query("stats")).optJSONObject("meta") ?: JSONObject()
        val lastFlush = meta.optLong("last_flush")
        RecorderStatus(
            recording = System.currentTimeMillis() / 1000 - lastFlush < STALE_AFTER_SECONDS,
            // Per kind of activity the daemon stores 1 (recording), 0 (can't
            // on this device) or -1 (not known yet, shortly after it starts).
            // Only a definite 0 is worth telling the user about.
            unavailable = meta.keys().asSequence()
                .filter { it.startsWith(COVERAGE_PREFIX) && meta.optLong(it) == 0L }
                .map { it.removePrefix(COVERAGE_PREFIX) }
                .sorted()
                .toList(),
        )
    }

    /**
     * Runs [block], reporting output that isn't the JSON we expect as a
     * failed command rather than letting the parse error escape: callers
     * only handle [RecordsException].
     */
    private inline fun <T> parsing(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw RecordsException.CommandFailed("unexpected output from bbd: ${e.message}")
    }

    private fun TimeRange.argument(): String = if (hours > 0) " --hours $hours" else ""

    private suspend fun query(args: String): String {
        val result = rootShell.run(
            "if [ -x $BBD ]; then $BBD $args; else echo $MISSING_MARKER >&2; false; fi",
            label = "bbd ${args.substringBefore(' ')}",
        )
        if (!result.isSuccess) {
            if (MISSING_MARKER in result.error) throw RecordsException.ModuleMissing()
            // How the daemon's argument parser rejects a command or option
            // it does not have, or an option repeated that it takes once:
            // the module is older than this app.
            if (UNKNOWN_ARGUMENT.any { it in result.error }) throw RecordsException.ModuleOutdated()
            throw RecordsException.CommandFailed(
                result.error.ifBlank { "bbd exited with ${result.code}" },
            )
        }
        return result.output
    }

    private companion object {
        const val MISSING_MARKER = "bbd-not-installed"
        val UNKNOWN_ARGUMENT = listOf(
            "unrecognized subcommand",
            "unexpected argument",
            "cannot be used multiple times",
        )
        const val COVERAGE_PREFIX = "coverage_"

        /** The daemon flushes every 5 s; allow several missed beats. */
        const val STALE_AFTER_SECONDS = 30
    }
}
