package li.mercury.bigbrother.core.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class CompareModulesTest {
    @Test
    fun givenOlderVersionCode_whenCompared_thenOutdated() {
        assertEquals(InstalledModule.OUTDATED, compareModules(7, "aa", 8, "bb"))
    }

    @Test
    fun givenNewerVersionCode_whenCompared_thenNewer() {
        assertEquals(InstalledModule.NEWER, compareModules(9, "aa", 8, "aa"))
    }

    @Test
    fun givenSameVersionAndDaemon_whenCompared_thenCurrentIgnoringDigestCase() {
        assertEquals(InstalledModule.CURRENT, compareModules(8, "AbC", 8, "abc"))
    }

    @Test
    fun givenSameVersionButDifferentDaemon_whenCompared_thenOutdated() {
        // A development build.
        assertEquals(InstalledModule.OUTDATED, compareModules(8, "abc", 8, "abd"))
    }

    @Test
    fun givenSameVersionWithoutDigest_whenCompared_thenCurrent() {
        // Without a digest there is nothing to tell them apart by.
        assertEquals(InstalledModule.CURRENT, compareModules(8, null, 8, "abc"))
    }
}
