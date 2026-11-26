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

import android.app.AppOpsManager
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import li.mercury.bigbrother.core.data.RecordsException
import li.mercury.bigbrother.core.data.bbd.TestBbdRootShell
import li.mercury.bigbrother.core.data.root.CommandResult
import li.mercury.bigbrother.core.data.toLoadFailure
import li.mercury.bigbrother.core.model.LoadFailure
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
// The SDKs CI provides offline are 24 and 37 (android/build.gradle.kts);
// these use package and app op APIs newer than 24.
@Config(sdk = [37])
class RootFileAccessRepositoryTest {
    private val app = RuntimeEnvironment.getApplication()

    @Before
    fun setUpStorage() {
        ShadowEnvironment.addExternalDir("primary")
    }

    @Test
    fun givenAllFilesAccess_whenEnsured_thenDoesNotCallRoot() = runTest {
        setStorageAppOp(AppOpsManager.MODE_ALLOWED)
        val shell = TestBbdRootShell { error("Unexpected root command") }

        RootFileAccessRepository(app, shell, StandardTestDispatcher(testScheduler)).ensureAllFilesAccess()

        assertTrue(shell.lines.isEmpty())
    }

    @Test
    fun givenNoFileAccess_whenRootGrantsIt_thenReturnsAfterCheckingThePermission() = runTest {
        setStorageAppOp(AppOpsManager.MODE_IGNORED)
        val shell = TestBbdRootShell {
            setStorageAppOp(AppOpsManager.MODE_ALLOWED)
            CommandResult(true, 0, "", "")
        }

        RootFileAccessRepository(app, shell, StandardTestDispatcher(testScheduler)).ensureAllFilesAccess()

        assertEquals(listOf("appops set '${app.packageName}' MANAGE_EXTERNAL_STORAGE allow"), shell.lines)
        assertEquals(listOf("grant all-files access"), shell.labels)
    }

    @Test
    fun givenRootCommandSucceedsWithoutGrant_whenEnsured_thenReportsAccessDenied() = runTest {
        setStorageAppOp(AppOpsManager.MODE_IGNORED)
        val shell = TestBbdRootShell("")
        val repository = RootFileAccessRepository(app, shell, StandardTestDispatcher(testScheduler))

        val failure = assertFailsWith<RecordsException.CommandFailed> { repository.ensureAllFilesAccess() }

        assertEquals(LoadFailure.Operation.FILE_ACCESS_DENIED, failure.toLoadFailure())
        assertEquals(1, shell.lines.size)
    }

    private fun setStorageAppOp(mode: Int) {
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode("android:manage_external_storage", app.applicationInfo.uid, app.packageName, mode)
    }
}
