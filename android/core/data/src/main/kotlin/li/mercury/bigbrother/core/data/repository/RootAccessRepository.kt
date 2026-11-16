package li.mercury.bigbrother.core.data.repository

import javax.inject.Inject
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.ModulePaths.BBD
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.data.root.shellQuoted
import li.mercury.bigbrother.core.model.Access
import li.mercury.bigbrother.core.model.AccessPeriod
import li.mercury.bigbrother.core.model.Action
import li.mercury.bigbrother.core.model.AppSummary
import li.mercury.bigbrother.core.model.Category
import li.mercury.bigbrother.core.model.TimeRange
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads records by running the daemon's query subcommands as root. The
 * daemon owns the database; its CLI prints JSON, which is the contract
 * between the two.
 */
internal class RootAccessRepository @Inject constructor(
    private val rootShell: RootShell,
) : AccessRepository {

    override suspend fun getApps(range: TimeRange): List<AppSummary> = parsing {
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

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else List(length()) { getString(it) }

    override suspend fun getAccesses(
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
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            // A category this build doesn't know is skipped rather than guessed at.
            val rowCategory = Category.fromId(row.getString("cat")) ?: return@mapNotNull null
            val mode = row.getInt("mode")
            Access(
                category = rowCategory,
                path = row.getString("path"),
                action = Action.fromMode(mode),
                viaProvider = Action.isViaProvider(mode),
                items = row.optInt("items"),
                allowed = row.getInt("ok"),
                denied = row.getInt("fail"),
                lastSeen = row.getLong("last_ts"),
                topic = row.optString("topic").ifEmpty { null },
            )
        }
    }

    override suspend fun getHistory(
        uid: Int,
        path: String,
        range: TimeRange,
    ): List<AccessPeriod> = parsing {
        // The path comes from a file name chosen by some app, so it is
        // quoted: it must reach the daemon as one argument, whatever it holds.
        val rows = JSONArray(
            query("history --uid $uid --path ${path.shellQuoted()}" + range.argument()),
        )
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

    override suspend fun isRecording(): Boolean = parsing {
        val lastFlush = JSONObject(query("stats")).optJSONObject("meta")?.optLong("last_flush") ?: 0
        System.currentTimeMillis() / 1000 - lastFlush < STALE_AFTER_SECONDS
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
        )
        if (!result.isSuccess) {
            if (MISSING_MARKER in result.error) throw RecordsException.ModuleMissing()
            throw RecordsException.CommandFailed(
                result.error.ifBlank { "bbd exited with ${result.code}" },
            )
        }
        return result.output
    }

    private companion object {
        const val MISSING_MARKER = "bbd-not-installed"

        /** The daemon flushes every 5 s; allow several missed beats. */
        const val STALE_AFTER_SECONDS = 30
    }
}
