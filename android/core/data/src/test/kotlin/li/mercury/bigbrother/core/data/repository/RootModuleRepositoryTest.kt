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

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.bbd.TestBbdRootShell
import li.mercury.bigbrother.core.data.root.CommandResult
import li.mercury.bigbrother.core.data.root.ModulePaths.LOCATE
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.LoadFailure
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
// The SDKs CI provides offline are 24 and 37 (android/build.gradle.kts);
// these use package and app op APIs newer than 24.
@Config(sdk = [37])
class RootModuleRepositoryTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun givenMatchingVersionAndPayload_whenCompared_thenBundledModuleIsCurrent() = runTest {
        val apk = directory.newFile("bundled.apk")
        val files = listOf("module.prop", "service.sh", "supervise.sh", "stop.sh", "uninstall.sh", "action.sh", "direct.sh")
        val bundledFiles = files + listOf("bin/x64/bbd", "helper.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            (bundledFiles + "classes.dex").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        app.applicationInfo.sourceDir = apk.path
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).setLongVersionCode(42)
        ShadowBuild.setSupportedAbis(arrayOf("x86_64"))
        val installedFiles = files + listOf("bin/bbd", "helper.apk")
        val contents = bundledFiles.map { it.toByteArray() }
        val hashes = installedFiles.zip(contents) { file, bytes ->
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            "$digest  /data/adb/modules/big_brother/$file"
        }
        var output = (listOf("42") + hashes).joinToString("\n")
        val shell = TestBbdRootShell { CommandResult(true, 0, output, "") }
        val repository = repository(shell)

        assertEquals(InstalledModule.CURRENT, repository.compareWithBundled())
        val legacyDigest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).toHexString()
        output = output.replace(hashes.last().substringBefore(' '), legacyDigest)
        assertEquals(InstalledModule.OUTDATED, repository.compareWithBundled())
        assertTrue(shell.lines.first().startsWith("$LOCATE; "))
        assertTrue(shell.lines.first().contains(installedFiles.joinToString(" ") { "\$bb/$it" }))
    }

    @Test
    fun givenNoReadout_whenCompared_thenBundledModuleIsNotInstalledOverIt() = runTest {
        // What a module the app installed prints while it is turned off.
        val shell = TestBbdRootShell("")

        assertEquals(InstalledModule.UNKNOWN, repository(shell).compareWithBundled())
        assertTrue(shell.lines.single().contains("[ \$bb != /data/adb/big_brother_direct ] || [ ! -e \$bb/disable ]"))
    }

    @Test
    fun givenModuleVersionReadouts_whenVersionIsRead_thenTrimsOutputAndReportsMissingModule() = runTest {
        val installed = repository(TestBbdRootShell(" v0.3.0 \nextra"))
        assertEquals("v0.3.0", installed.installedVersion())

        val missing = repository(TestBbdRootShell("", error = "module-version-missing"))
        assertFailsWith<RecordsException.ModuleMissing> { missing.installedVersion() }
    }

    @Test
    fun givenDisabledModule_whenUpdatedOrStarted_thenDoesNotRunItsScripts() = runTest {
        val shell = TestBbdRootShell("")
        val repository = repository(shell)

        val updateFailure = assertFailsWith<RecordsException.CommandFailed> { repository.updateInPlace() }
        val startFailure = assertFailsWith<RecordsException.CommandFailed> { repository.startDaemon() }

        assertEquals(LoadFailure.Operation.MODULE_DISABLED, updateFailure.toLoadFailure())
        assertEquals(LoadFailure.Operation.MODULE_DISABLED, startFailure.toLoadFailure())
        val check = "$LOCATE; [ -e \$bb/disable ] || [ -e \$bb/remove ]"
        // Only a module the app installed is turned on again by starting it.
        val enable = "$LOCATE; [ \$bb != /data/adb/big_brother_direct ] || rm -f /data/adb/big_brother_direct/disable"
        assertEquals(listOf(check, enable, check), shell.lines)
    }

    @Test
    fun givenModuleTurnedOffByItsBootGuard_whenStartedWithoutTurningOn_thenStaysOff() = runTest {
        val shell = TestBbdRootShell("")

        val failure = assertFailsWith<RecordsException.CommandFailed> { repository(shell).startDaemon(turnOn = false) }

        assertEquals(LoadFailure.Operation.MODULE_DISABLED, failure.toLoadFailure())
        assertEquals(listOf("$LOCATE; [ -e \$bb/disable ] || [ -e \$bb/remove ]"), shell.lines)
    }

    @Test
    fun givenInstallerFailure_whenBundledModuleIsInstalled_thenMapsItToTheInstallerReason() = runTest {
        app.applicationInfo.sourceDir = directory.newFile("bundled module.apk").path
        val shell = TestBbdRootShell { CommandResult(false, 1, "- Installing with root alone\n! Kernel 5.4 is too old; 5.10 or newer required", "") }

        val failure = assertFailsWith<RecordsException.CommandFailed> { repository(shell).installBundled() }

        assertEquals(LoadFailure.Operation.KERNEL_TOO_OLD, failure.toLoadFailure())
        assertEquals(listOf("module install"), shell.labels)
        // The installer is the app's own, taken out of its package; no root manager is asked.
        val apk = "'${app.applicationInfo.sourceDir}'"
        assertTrue(shell.lines.single().contains("unzip -p $apk direct.sh >\"\$d/direct.sh\" && sh \"\$d/direct.sh\" install $apk"))
    }

    @Test
    fun givenModuleInstalledByApp_whenRemoved_thenRunsItsOwnScriptAndReportsFailure() = runTest {
        var removed = true
        val shell = TestBbdRootShell { CommandResult(removed, if (removed) 0 else 1, "", "") }
        val repository = repository(shell)

        repository.remove()
        removed = false
        val failure = assertFailsWith<RecordsException.CommandFailed> { repository.remove() }

        assertEquals(LoadFailure.Operation.MODULE_REMOVE_FAILED, failure.toLoadFailure())
        assertEquals(List(2) { "module remove" }, shell.labels)
        // A module in the root manager is removed there, never from here.
        assertTrue(shell.lines.all { it.startsWith("$LOCATE; [ \$bb = /data/adb/big_brother_direct ] && sh /data/adb/big_brother_direct/direct.sh remove") })
    }

    private fun TestScope.repository(shell: TestBbdRootShell) = RootModuleRepository(app, shell, StandardTestDispatcher(testScheduler))
}
