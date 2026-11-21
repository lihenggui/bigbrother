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

package li.mercury.bigbrother.core.data.root

import li.mercury.bigbrother.core.data.RecordsException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BbdFailureTest {
    @Test
    fun givenUnknownSubcommand_whenClassified_thenModuleOutdated() {
        assertIs<RecordsException.ModuleOutdated>(clapFailure("unknown-subcommand"))
    }

    @Test
    fun givenUnknownOption_whenClassified_thenModuleOutdated() {
        assertIs<RecordsException.ModuleOutdated>(clapFailure("unknown-option"))
    }

    @Test
    fun givenRepeatedOption_whenClassified_thenModuleOutdated() {
        assertIs<RecordsException.ModuleOutdated>(clapFailure("repeated-option"))
    }

    @Test
    fun givenInvalidValue_whenClassified_thenCommandFailedWithDiagnostic() {
        for (kind in listOf("invalid-number", "invalid-enum")) {
            val failure = assertIs<RecordsException.CommandFailed>(clapFailure(kind))
            assertEquals(clapDiagnostic(kind), failure.message)
        }
    }

    @Test
    fun givenUnknownDaemonRequest_whenClassified_thenModuleOutdated() {
        val result = CommandResult(isSuccess = false, code = 1, output = "", error = "Error: unknown command")

        assertIs<RecordsException.ModuleOutdated>(result.bbdFailure())
    }

    @Test
    fun givenMissingMarkerInStderrOrStdout_whenClassified_thenModuleMissing() {
        val marker = "bbd-not-installed"
        val stderr = CommandResult(isSuccess = false, code = 1, output = "", error = marker)
        val stdout = CommandResult(isSuccess = false, code = 1, output = marker, error = "")

        assertIs<RecordsException.ModuleMissing>(stderr.bbdFailure())
        assertIs<RecordsException.ModuleMissing>(stdout.bbdFailure())
    }

    @Test
    fun givenGenericError_whenClassified_thenCommandFailedWithReason() {
        val result = CommandResult(isSuccess = false, code = 1, output = "", error = "Error: the daemon is not running")

        assertEquals("the daemon is not running", assertIs<RecordsException.CommandFailed>(result.bbdFailure()).message)
    }

    @Test
    fun givenSilentFailure_whenClassified_thenCommandFailedWithExitCode() {
        val result = CommandResult(isSuccess = false, code = 126, output = "", error = "")

        assertEquals("bbd exited with 126", assertIs<RecordsException.CommandFailed>(result.bbdFailure()).message)
    }

    @Test
    fun givenSuccessfulResult_whenClassified_thenNoFailure() {
        val result = CommandResult(isSuccess = true, code = 0, output = "[]", error = "bbd-not-installed")

        assertNull(result.bbdFailure())
    }

    private fun clapFailure(kind: String): RecordsException? = CommandResult(isSuccess = false, code = 2, output = "", error = clapDiagnostic(kind)).bbdFailure()

    private fun clapDiagnostic(kind: String): String = "${cliErrors.getValue(kind)}\n\nFor more information, try '--help'."

    private companion object {
        val cliErrors = File("../../../testdata/protocol/bbd-cli-errors.txt").useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }
                .associate { line ->
                    val (kind, diagnostic) = line.split('\t', limit = 3)
                    kind to diagnostic
                }
        }
    }
}
