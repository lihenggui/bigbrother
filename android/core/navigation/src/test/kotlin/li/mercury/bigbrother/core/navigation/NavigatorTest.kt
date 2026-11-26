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

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class NavigatorTest {
    private val navigator = Navigator(testNavigationState())
    private val state = navigator.state

    @Test
    fun givenChildOnBackStack_whenNavigatedToAgain_thenMovesToTopWithoutDuplicate() {
        navigator.navigate(TestKey.FIRST)
        navigator.navigate(TestKey.SECOND)
        navigator.navigate(TestKey.FIRST)

        assertEquals(listOf(TestKey.START, TestKey.SECOND, TestKey.FIRST), state.currentSubStack.toList())
    }

    @Test
    fun givenChildAndPriorTopLevel_whenGoingBack_thenPopsChildThenTopLevel() {
        navigator.navigate(TestKey.FIRST)
        navigator.navigate(TestKey.OTHER)
        navigator.navigate(TestKey.SECOND)

        navigator.goBack()
        assertEquals(TestKey.OTHER, state.currentKey)
        navigator.goBack()
        assertEquals(TestKey.FIRST, state.currentKey)
        navigator.goBack()
        navigator.goBack()
        assertEquals(TestKey.START, state.currentKey)
    }

    @Test
    fun givenTopLevelWithChild_whenReselected_thenReturnsToItsRoot() {
        navigator.navigate(TestKey.OTHER)
        navigator.navigate(TestKey.FIRST)

        navigator.navigate(TestKey.OTHER)

        assertEquals(listOf(TestKey.OTHER), state.currentSubStack.toList())
        assertEquals(listOf(TestKey.START, TestKey.OTHER), state.topLevelStack.toList())
    }
}

internal enum class TestKey : NavKey { START, OTHER, FIRST, SECOND }

internal fun testNavigationState(): NavigationState = NavigationState(
    startKey = TestKey.START,
    topLevelStack = NavBackStack(TestKey.START),
    subStacks = mapOf(TestKey.START to NavBackStack(TestKey.START), TestKey.OTHER to NavBackStack(TestKey.OTHER)),
)
