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

package li.mercury.bigbrother.catalog

import java.io.File

/**
 * One case of `testdata/protocol/` at the repository's root, which the
 * daemon's tests read as well (`bbd/src/protocol_cases.rs`): its [type],
 * the [line], and the words of its [meaning], each a key and a value
 * (empty for a word without `=`). The format is in its `README.md`.
 */
internal class ProtocolCase(val type: String, val line: String, val meaning: List<Pair<String, String>>) {
    /** The value of the one word [key], if there is one. */
    fun get(key: String): String? = all(key).also { check(it.size <= 1) { "$key twice in $line" } }.firstOrNull()

    /** The values of the words [key], in order. */
    fun all(key: String): List<String> = meaning.filter { it.first == key }.map { it.second }

    /** The first word, which names what the line is in some formats. */
    val first: String get() = meaning.first().first
}

/**
 * The cases of `testdata/protocol/<name>`, each of one of its [types] and
 * with words of its [keys] only: a case the tests would not look at, or a
 * word they would not read, is a mistake in the file.
 */
internal fun protocolCases(name: String, types: Set<String>, keys: Set<String>): List<ProtocolCase> {
    val cases = File(protocolDirectory(), name).readLines()
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .map { text ->
            val fields = text.split('\t')
            val meaning = fields.drop(2).map { word ->
                val key = word.substringBefore('=')
                key to unescape(word.substringAfter('=', missingDelimiterValue = ""))
            }
            // An empty line has nothing after its type.
            ProtocolCase(fields[0], unescape(fields.getOrElse(1) { "" }), meaning)
        }
    for (case in cases) {
        check(case.type in types) { "unknown type ${case.type} of ${case.line} in $name" }
        for ((key, _) in case.meaning) {
            check(key in keys) { "unknown word $key in ${case.line} in $name" }
        }
    }
    return cases
}

/** The cases of any of [types], of which there must be at least one. */
internal fun List<ProtocolCase>.ofType(vararg types: String): List<ProtocolCase> = filter { it.type in types }.also { check(it.isNotEmpty()) { "no case of ${types.toList()}" } }

internal fun appOpsTransactionCases(): List<ProtocolCase> = protocolCases(
    "appops-transaction.txt",
    types = setOf("request", "reply"),
    keys = setOf("request", "command", "token", "answer"),
)

/** Found from the directory the tests run in, which is the module's. */
private fun protocolDirectory(): File {
    val start = File("").absoluteFile
    return generateSequence(start) { it.parentFile }
        .map { File(it, PROTOCOL_DIRECTORY) }
        .firstOrNull { it.isDirectory }
        ?: error("no $PROTOCOL_DIRECTORY in $start or a directory above it")
}

private const val PROTOCOL_DIRECTORY = "testdata/protocol"

/** `\t`, `\n` and `\\` as the characters they stand for. */
private fun unescape(text: String): String = buildString {
    var escaped = false
    for (c in text) {
        when {
            escaped -> {
                append(
                    when (c) {
                        't' -> '\t'
                        'n' -> '\n'
                        '\\' -> '\\'
                        else -> error("unknown escape \\$c in $text")
                    },
                )
                escaped = false
            }

            c == '\\' -> escaped = true

            else -> append(c)
        }
    }
    check(!escaped) { "a backslash at the end of $text" }
}
