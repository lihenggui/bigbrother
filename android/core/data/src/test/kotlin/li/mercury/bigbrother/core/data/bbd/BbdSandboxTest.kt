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

package li.mercury.bigbrother.core.data.bbd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.model.SandboxItem
import li.mercury.bigbrother.core.model.SandboxMode
import li.mercury.bigbrother.core.model.SandboxPart
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class BbdSandboxTest {
    @Test
    fun givenListWithUnknownItemsAndModes_whenRead_thenKeepsSupportedValues() = runTest {
        val shell = TestBbdRootShell(bbdFixture("sandbox-apps"))

        val app = RootShellBbdDataSource(shell, Dispatchers.Unconfined).listSandboxApps().single()

        assertEquals("com.example.notes", app.packageName)
        assertEquals(2, app.networkRules)
        assertEquals(true, app.appOpsChecking)
        assertEquals(mapOf(SandboxItem.CAMERA to SandboxMode.OFF), app.settings)
        assertEquals(mapOf(SandboxItem.CAMERA to SandboxMode.OFF), app.items)
        assertEquals(mapOf(SandboxItem.CAMERA to setOf(SandboxPart.APP_OPS)), app.unmet)
        assertNull(app.recordingSince)
        assertContains(shell.lines.single(), "sandbox list --json;")
    }

    @Test
    fun givenGlobalSettings_whenRead_thenReturnsModesAndAutoFlag() = runTest {
        val shell = TestBbdRootShell(bbdFixture("sandbox-global"))

        val global = RootShellBbdDataSource(shell, Dispatchers.Unconfined).getSandboxGlobal()

        assertEquals(BbdSandboxGlobal(mapOf(SandboxItem.CAMERA to SandboxMode.OFF), true), global)
        assertContains(shell.lines.single(), "sandbox global --json;")
    }

    @Test
    fun givenStatusWithHooks_whenRead_thenReturnsFeatureAndHookState() = runTest {
        val shell = TestBbdRootShell(bbdFixture("sandbox-status"))

        val status = RootShellBbdDataSource(shell, Dispatchers.Unconfined).getSandboxStatus()

        assertEquals(
            BbdSandboxStatus(true, false, true, false, true, false, 2, true, true, true, false, true, newAppsFailed = 1),
            status,
        )
        assertContains(shell.lines.single(), "sandbox status --json;")
    }

    @Test
    fun givenSandboxMutations_whenRun_thenKeepsSubcommandsAndFlags() = runTest {
        val shell = TestBbdRootShell("")
        val source = RootShellBbdDataSource(shell, Dispatchers.Unconfined)
        val pkg = "com.example.it's"

        source.enableSandbox(pkg, 10)
        source.disableSandbox(pkg, 10)
        source.setSandboxItems(pkg, 10, mapOf(SandboxItem.CAMERA to null, SandboxItem.LOCATION to SandboxMode.COARSE))
        source.setSandboxGlobal(mapOf(SandboxItem.CAMERA to SandboxMode.OFF))
        source.setSandboxAuto(true)
        source.setSandboxAuto(false)

        val quoted = """'com.example.it'\''s'"""
        assertEquals(
            listOf(
                "enable $quoted --user 10",
                "disable $quoted --user 10",
                "set $quoted camera=global location=coarse --user 10",
                "global camera=off",
                "auto on",
                "auto off",
            ),
            shell.lines.map { it.substringAfter(" sandbox ").substringBefore(" --json;") },
        )
        assertEquals(listOf("enable", "disable", "set", "global", "auto", "auto").map { "bbd sandbox $it" }, shell.labels)
    }

    @Test
    fun givenPartialRelease_whenDisabled_thenCountsFailedPackages() = runTest {
        val shell = TestBbdRootShell(bbdFixture("sandbox-release-all"))

        assertEquals(BbdSandboxReleaseResult(1), RootShellBbdDataSource(shell, Dispatchers.Unconfined).disableAllSandboxes())
        assertContains(shell.lines.single(), "sandbox disable --all --json;")
    }

    @Test
    fun givenReleaseWithoutFailuresArray_whenDecoded_thenThrowsBadResponse() = runTest {
        val shell = TestBbdRootShell(bbdFixture("sandbox-release-all-malformed"))

        val source = RootShellBbdDataSource(shell, Dispatchers.Unconfined)

        assertFailsWith<RecordsException.BadResponse> { source.disableAllSandboxes() }
    }

    @Test
    fun givenModuleWithoutAutoCommand_whenAutoIsolateTurnedOn_thenModuleOutdated() = runTest {
        val shell = TestBbdRootShell("", error = "error: unrecognized subcommand 'auto'")

        assertFailsWith<RecordsException.ModuleOutdated> { RootShellBbdDataSource(shell, Dispatchers.Unconfined).setSandboxAuto(true) }
    }
}
