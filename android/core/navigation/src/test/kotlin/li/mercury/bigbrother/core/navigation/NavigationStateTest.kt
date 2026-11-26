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

package li.mercury.bigbrother.core.navigation

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class NavigationStateTest {
    @Test
    fun givenInitialStacks_whenConstructed_thenStartIsViewedAndBothTopLevelsAreKnown() {
        val state = testNavigationState()

        assertEquals(TestKey.START, state.currentKey)
        assertEquals(TestKey.START, state.currentTopLevelKey)
        assertEquals(setOf(TestKey.START, TestKey.OTHER), state.topLevelKeys)
    }

    @Test
    fun givenDifferentChildrenPerTopLevel_whenStackChanges_thenCurrentKeyTracksActiveChild() {
        val state = testNavigationState()
        state.subStacks.getValue(TestKey.START).add(TestKey.FIRST)
        state.subStacks.getValue(TestKey.OTHER).add(TestKey.SECOND)
        assertEquals(TestKey.FIRST, state.currentKey)

        state.topLevelStack.add(TestKey.OTHER)

        assertEquals(TestKey.OTHER, state.currentTopLevelKey)
        assertSame(state.subStacks[TestKey.OTHER], state.currentSubStack)
        assertEquals(TestKey.SECOND, state.currentKey)
        state.currentSubStack.removeAt(1)
        assertEquals(TestKey.OTHER, state.currentKey)
    }
}
