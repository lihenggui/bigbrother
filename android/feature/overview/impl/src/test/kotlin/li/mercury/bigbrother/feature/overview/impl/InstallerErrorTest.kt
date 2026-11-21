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

package li.mercury.bigbrother.feature.overview.impl

import li.mercury.bigbrother.core.data.repository.installerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallerErrorTest {
    @Test
    fun givenOutputWithErrorLines_whenTrimmed_thenKeepsOnlyErrorLines() {
        val output = """
            - Device is system-as-root
            *******************
             Powered by Magisk
            *******************
            - Installing on Magisk 31.1
            ! Kernel has no BTF (/sys/kernel/btf/vmlinux); GKI 5.10+ required
        """.trimIndent()
        assertEquals("Kernel has no BTF (/sys/kernel/btf/vmlinux); GKI 5.10+ required", installerError(output))
    }

    @Test
    fun givenOutputWithoutErrorLines_whenTrimmed_thenFallsBackToLastLine() {
        assertEquals("No module installer found (KernelSU, Magisk or APatch).", installerError("\nNo module installer found (KernelSU, Magisk or APatch).\n"))
        assertNull(installerError("  \n"))
    }
}
