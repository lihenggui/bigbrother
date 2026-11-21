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
@Config(sdk = [36])
class RootModuleRepositoryTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun givenMatchingVersionAndPayload_whenCompared_thenBundledModuleIsCurrent() = runTest {
        val apk = directory.newFile("bundled.apk")
        val files = listOf("module.prop", "service.sh", "supervise.sh", "stop.sh", "uninstall.sh", "action.sh", "bin/x64/bbd")
        ZipOutputStream(apk.outputStream()).use { zip ->
            files.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        app.applicationInfo.sourceDir = apk.path
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).setLongVersionCode(42)
        ShadowBuild.setSupportedAbis(arrayOf("x86_64"))
        val installedFiles = files.dropLast(1) + listOf("bin/bbd", "helper.apk")
        val contents = files.map { it.toByteArray() } + listOf(apk.readBytes())
        val hashes = installedFiles.zip(contents) { file, bytes ->
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            "$digest  /data/adb/modules/big_brother/$file"
        }
        var output = (listOf("42") + hashes).joinToString("\n")
        val shell = TestBbdRootShell { CommandResult(true, 0, output, "") }
        val repository = repository(shell)

        assertEquals(InstalledModule.CURRENT, repository.compareWithBundled())
        output = output.replace(hashes.last().substringBefore(' '), "0".repeat(64))
        assertEquals(InstalledModule.OUTDATED, repository.compareWithBundled())
        assertTrue(shell.lines.first().contains(installedFiles.joinToString(" ") { "/data/adb/modules/big_brother/$it" }))
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
        assertEquals(List(2) { "[ -e /data/adb/modules/big_brother/disable ] || [ -e /data/adb/modules/big_brother/remove ]" }, shell.lines)
    }

    @Test
    fun givenInstallerFailure_whenBundledModuleIsInstalled_thenMapsItToTheInstallerReason() = runTest {
        app.applicationInfo.sourceDir = directory.newFile("bundled module.apk").path
        val shell = TestBbdRootShell { CommandResult(false, 1, "No module installer found (KernelSU, Magisk or APatch).", "") }

        val failure = assertFailsWith<RecordsException.CommandFailed> { repository(shell).installBundled() }

        assertEquals(LoadFailure.Operation.INSTALLER_MISSING, failure.toLoadFailure())
        assertEquals(listOf("module install"), shell.labels)
        assertTrue(shell.lines.single().contains("magisk --install-module '${app.applicationInfo.sourceDir}'"))
    }

    private fun TestScope.repository(shell: TestBbdRootShell) = RootModuleRepository(app, shell, StandardTestDispatcher(testScheduler))
}
