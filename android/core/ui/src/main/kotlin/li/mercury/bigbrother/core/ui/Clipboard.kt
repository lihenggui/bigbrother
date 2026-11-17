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

package li.mercury.bigbrother.core.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import li.mercury.bigbrother.core.model.ClipboardCounts

/**
 * Which way an app used the clipboard, reads and listening before writes:
 * "Read 3 times · Listens for changes · Written once", or, [compact], for a
 * line shared with more, "Read 3× · Listens · Written 1×". Reads and writes
 * the system refused are counted with the rest and said how many ("Read 3
 * times (1 denied)"), or on their own if it refused all.
 */
@Composable
fun clipboardSummary(counts: ClipboardCounts, compact: Boolean = false): String {
    val parts = if (compact) {
        listOfNotNull(
            countWithDenied(counts.read, counts.readDenied, R.string.clipboard_read_short, R.string.clipboard_read_denied_short),
            if (counts.listened > 0) stringResource(R.string.clipboard_listens_short) else null,
            countWithDenied(counts.written, counts.writeDenied, R.string.clipboard_written_short, R.string.clipboard_write_denied_short),
        )
    } else {
        listOfNotNull(
            pluralsWithDenied(counts.read, counts.readDenied, R.plurals.clipboard_read, R.plurals.clipboard_read_denied),
            if (counts.listened > 0) stringResource(R.string.clipboard_listens) else null,
            pluralsWithDenied(counts.written, counts.writeDenied, R.plurals.clipboard_written, R.plurals.clipboard_write_denied),
        )
    }
    return parts.joinToString(stringResource(R.string.dot_separator))
}

@Composable
private fun pluralsWithDenied(allowed: Int, denied: Int, @PluralsRes done: Int, @PluralsRes refused: Int): String? = when {
    allowed > 0 && denied > 0 -> stringResource(R.string.clipboard_partly_denied, pluralStringResource(done, allowed + denied, allowed + denied), denied)
    allowed > 0 -> pluralStringResource(done, allowed, allowed)
    denied > 0 -> pluralStringResource(refused, denied, denied)
    else -> null
}

@Composable
private fun countWithDenied(allowed: Int, denied: Int, @StringRes done: Int, @StringRes refused: Int): String? = when {
    allowed > 0 && denied > 0 -> stringResource(R.string.clipboard_partly_denied, stringResource(done, allowed + denied), denied)
    allowed > 0 -> stringResource(done, allowed)
    denied > 0 -> stringResource(refused, denied)
    else -> null
}
