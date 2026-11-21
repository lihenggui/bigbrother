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

package li.mercury.bigbrother.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class TestMethodNameDetectorTest {
    @Test
    fun givenMatchingName_whenLintChecksUnitTest_thenNoErrorIsReported() {
        lint().issues(TestMethodNameDetector.ISSUE)
            .files(
                junitTest,
                kotlin(
                    "test/example/MatchingTest.kt",
                    """
                    package example
                    import org.junit.Test

                    class MatchingTest {
                        @Test
                        fun givenSavedState2_whenScreenOpens_thenPreview_1Appears() = Unit
                    }
                    """,
                ).indented(),
            )
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    @Test
    fun givenInvalidNames_whenLintChecksUnitTests_thenEachNameIsReported() {
        lint().issues(TestMethodNameDetector.ISSUE)
            .files(
                junitTest,
                kotlin(
                    "test/example/InvalidTest.kt",
                    """
                    package example
                    import org.junit.Test

                    class InvalidTest {
                        @Test
                        fun givenstate_whenAction_thenOutcome() = Unit
                        @Test
                        fun givenState_thenOutcome() = Unit
                        @Test
                        fun `givenState_whenAction_thenOutcome with spaces`() = Unit
                    }
                    """,
                ).indented(),
            )
            .allowMissingSdk()
            .run()
            .expectErrorCount(3)
            .expectContains("Error: Name Kotlin tests givenState_whenAction_thenOutcome [TestMethodName]")
    }

    @Test
    fun givenAliasedTestAnnotation_whenLintChecksInstrumentationTest_thenInvalidNameIsReported() {
        lint().issues(TestMethodNameDetector.ISSUE)
            .files(
                junitTest,
                kotlin(
                    "androidTest/example/InvalidTest.kt",
                    """
                    package example
                    import org.junit.Test as JUnitTest

                    typealias Test = JUnitTest

                    class InvalidTest {
                        @Test
                        fun invalidName() = Unit
                    }
                    """,
                ).indented(),
            )
            .allowMissingSdk()
            .run()
            .expectErrorCount(1)
    }

    @Test
    fun givenHelpersAndMainSources_whenLintChecksNames_thenNoErrorIsReported() {
        lint().issues(TestMethodNameDetector.ISSUE)
            .files(
                junitTest,
                kotlin(
                    "src/example/Main.kt",
                    """
                    package example
                    import org.junit.Test

                    class Main {
                        @Test
                        fun sample() = Unit
                    }
                    """,
                ).indented(),
                kotlin(
                    "test/example/Helpers.kt",
                    """
                    package example

                    annotation class Test

                    class Helpers {
                        @Test
                        fun usesDifferentAnnotation() = Unit
                        fun helper() = Unit
                    }
                    """,
                ).indented(),
            )
            .allowMissingSdk()
            .run()
            .expectClean()
    }

    private companion object {
        val junitTest = java(
            """
            package org.junit;

            public @interface Test {}
            """,
        ).indented()
    }
}
