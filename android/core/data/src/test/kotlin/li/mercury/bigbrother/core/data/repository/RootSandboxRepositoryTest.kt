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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.root.CommandResult
import li.mercury.bigbrother.core.data.root.RootShell
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RootSandboxRepositoryTest {
    private val shell = TestRootShell(failing = setOf("list"))
    private val repository = RootSandboxRepository(shell)

    @Test
    fun givenListUnreadable_whenIsolated_thenNotFailedAndListKept() = runTest {
        repository.isolate("com.example.notes", userId = 0)

        assertEquals(listOf("enable", "list"), shell.commands)
        assertNull(repository.apps.value)
    }

    @Test
    fun givenListUnreadable_whenReleasedOrChanged_thenNotFailed() = runTest {
        repository.release("com.example.notes", userId = 0)
        repository.setItems("com.example.notes", userId = 0, mapOf(SandboxItem.CAMERA to SandboxMode.OFF))
        repository.setGlobal(mapOf(SandboxItem.LOCATION to SandboxMode.COARSE))

        assertEquals(listOf("disable", "list", "set", "list", "global", "list"), shell.commands)
    }

    @Test
    fun givenListUnreadable_whenAllReleased_thenNoneLeftReported() = runTest {
        assertEquals(0, repository.releaseAll())
    }

    @Test
    fun givenCommandFailing_whenIsolated_thenFailsWithoutReadingList() = runTest {
        val shell = TestRootShell(failing = setOf("enable"))

        assertFailsWith<RecordsException.CommandFailed> { RootSandboxRepository(shell).isolate("com.example.notes", userId = 0) }
        assertEquals(listOf("enable"), shell.commands)
    }

    /** Answers `bbd sandbox` as the daemon would, failing the subcommands in [failing]. */
    private class TestRootShell(private val failing: Set<String>) : RootShell(Dispatchers.Unconfined) {
        /** The subcommands run, in order. */
        val commands = mutableListOf<String>()

        override suspend fun run(command: String, label: String): CommandResult {
            val subcommand = label.removePrefix("bbd sandbox ")
            commands += subcommand
            return if (subcommand in failing) {
                CommandResult(isSuccess = false, code = 1, output = "", error = "Error: cannot read the sandbox")
            } else {
                CommandResult(isSuccess = true, code = 0, output = "{}", error = "")
            }
        }
    }
}
