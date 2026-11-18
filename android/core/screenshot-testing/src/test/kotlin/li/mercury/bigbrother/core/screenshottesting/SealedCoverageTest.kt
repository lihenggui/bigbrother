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

package li.mercury.bigbrother.core.screenshottesting

import li.mercury.bigbrother.core.model.LoadFailure
import org.junit.Test
import kotlin.test.assertFailsWith

class SealedCoverageTest {
    @Test
    fun givenDemoLoadFailures_whenChecked_thenEveryKindHasASample() {
        assertSamplesCover(LoadFailure::class.java, DemoLoadFailures)
    }

    @Test
    fun givenKindWithoutSample_whenChecked_thenFails() {
        assertFailsWith<AssertionError> {
            assertSamplesCover(LoadFailure::class.java, DemoLoadFailures - LoadFailure.ModuleOutdated)
        }
    }

    @Test
    fun givenKindWithoutSample_whenExcluded_thenPasses() {
        assertSamplesCover(
            LoadFailure::class.java,
            DemoLoadFailures - LoadFailure.ModuleOutdated,
            excluded = setOf(LoadFailure.ModuleOutdated::class.java),
        )
    }

    @Test
    fun givenNestedSealedType_whenChecked_thenCountsItsSubclasses() {
        assertSamplesCover(Shape::class.java, listOf(Shape.Round.Circle, Shape.Round.Oval, Shape.Square))
        assertFailsWith<AssertionError> {
            assertSamplesCover(Shape::class.java, listOf(Shape.Round.Circle, Shape.Square))
        }
    }

    private sealed interface Shape {
        sealed interface Round : Shape {
            data object Circle : Round
            data object Oval : Round
        }

        data object Square : Shape
    }
}
