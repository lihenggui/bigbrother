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

package li.mercury.bigbrother.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class EventSpoolTest {
    @Test
    fun givenEveryRecordOfTheCases_whenWritten_thenTheLineTheDaemonReads() {
        val cases = protocolCases(
            "spool.txt",
            types = setOf("line", "read", "bad"),
            keys = setOf("ts", "uid", "op", "mode", "read", "write", "items", "uri", "path"),
        )
        for (case in cases.ofType("line")) {
            val time = case.get("ts")!!.toLong()
            val uid = case.get("uid")!!.toInt()
            val uri = case.get("uri")!!
            val line = when (val op = case.get("op")) {
                "query" -> EventSpool.queryLine(time, uid, uri, case.get("items")!!.toInt())
                "open" -> EventSpool.openLine(time, uid, uri, case.get("mode")!!, case.get("path")!!)
                else -> error("no op $op")
            }
            assertEquals(case.line, line)
        }
    }

    @Test
    fun givenTabsAndLineBreaksInAPath_whenWritten_thenSpacesInTheirPlace() {
        assertEquals(
            "1\t1700000000\t10123\to\tr\t0\tcontent://media/1\t/storage/a b c d",
            EventSpool.openLine(1_700_000_000, 10123, "content://media/1", "r", "/storage/a\tb\nc\rd"),
        )
    }

    @Test
    fun givenUnknownAuthority_whenOpened_thenNoPrivatePathIsQueued() {
        assertEquals("", EventSpool.openLine(1, 10123, "content://example/1", "r", "/private/item"))
    }

    @Test
    fun givenPersonalOpen_whenSerialized_thenOnlyCollectionIsKept() {
        assertEquals(
            "1\t1\t10123\to\tr\t0\tcontent://com.android.contacts/contacts\t",
            EventSpool.openLine(1, 10123, "content://0@com.android.contacts/contacts/42?q=private", "r", "/private/item"),
        )
    }
}
