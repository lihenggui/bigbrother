package li.mercury.bigbrother.feature.overview.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallerErrorTest {
    @Test
    fun keeps_the_lines_marked_as_errors() {
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
    fun falls_back_to_the_last_line() {
        assertEquals("No module installer found (KernelSU, Magisk or APatch).", installerError("\nNo module installer found (KernelSU, Magisk or APatch).\n"))
        assertNull(installerError("  \n"))
    }
}
