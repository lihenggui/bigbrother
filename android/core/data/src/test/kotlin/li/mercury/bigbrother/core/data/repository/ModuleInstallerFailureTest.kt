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

import li.mercury.bigbrother.core.model.LoadFailure
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleInstallerFailureTest {
    @Test
    fun givenInstallerOutput_whenClassified_thenKeepsTheSpecificReason() {
        val examples = mapOf(
            "! Kernel has no BTF (/sys/kernel/btf/vmlinux); GKI 5.10+ required" to LoadFailure.Operation.KERNEL_BTF_MISSING,
            "! Kernel 5.4 is too old; 5.10 or newer required" to LoadFailure.Operation.KERNEL_TOO_OLD,
            "No module installer found (KernelSU, Magisk or APatch)." to LoadFailure.Operation.INSTALLER_MISSING,
            "! This module requires KernelSU, Magisk or APatch" to LoadFailure.Operation.INSTALLER_MISSING,
            "! Unsupported architecture: arm" to LoadFailure.Operation.UNSUPPORTED_ARCHITECTURE,
            "! Failed to extract the daemon for arm64" to LoadFailure.Operation.MODULE_ARCHIVE_INVALID,
            "! Error: unsupported database schema 6 (expected 5)" to LoadFailure.Operation.DATABASE_INCOMPATIBLE,
            "Module updated; daemon startup was not confirmed" to LoadFailure.Operation.DAEMON_NOT_REPORTING,
        )
        examples.forEach { (line, expected) ->
            assertEquals(expected, moduleInstallerFailure("- Extracting module files\n$line\n! Installation failed", LoadFailure.Operation.MODULE_INSTALL_FAILED))
        }
    }

    @Test
    fun givenUnrecognizedInstallerOutput_whenClassified_thenUsesTheOperationFailure() {
        assertEquals(LoadFailure.Operation.MODULE_UPDATE_FAILED, moduleInstallerFailure("unzip: /data/app/module.apk", LoadFailure.Operation.MODULE_UPDATE_FAILED))
        assertEquals(LoadFailure.Operation.MODULE_INSTALL_FAILED, moduleInstallerFailure("", LoadFailure.Operation.MODULE_INSTALL_FAILED))
    }
}
