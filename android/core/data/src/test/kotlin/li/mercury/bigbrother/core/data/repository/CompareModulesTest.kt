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
