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

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

class SourceSizeDetectorTest {
    @Test
    fun givenFunctionsAtAndAboveLimit_whenLintChecksMainSource_thenLongFunctionsAreReported() {
        lint().issues(LongFunctionDetector.ISSUE)
            .files(
                kotlin(
                    "src/example/Sample.kt",
                    """package example

fun atLimit() {
${statements(78)}
}
fun aboveLimit() {
${statements(79)}
}
fun expression() = run {
${statements(79)}
}
fun withLocalFunction() {
    fun local() {
${statements(79, "        ")}
    }
}
""",
                ),
            )
            .allowMissingSdk()
            .testModes(TestMode.DEFAULT)
            .run()
            .expectErrorCount(4)
            .expectContains("Function spans 81 lines (limit 80)")
    }

    @Test
    fun givenMultilineDeclaration_whenLintChecksFunction_thenSignatureLinesAreCounted() {
        lint().issues(LongFunctionDetector.ISSUE)
            .files(
                kotlin(
                    "src/example/Signature.kt",
                    """package example

fun longSignature(
    number: Int,
) = run {
${statements(77)}
    number
}
""",
                ),
            )
            .allowMissingSdk()
            .testModes(TestMode.DEFAULT)
            .run()
            .expectErrorCount(1)
            .expectContains("Function spans 82 lines (limit 80)")
    }

    @Test
    fun givenFilesAtAndAboveLimits_whenLintChecksMainSources_thenEachLimitApplies() {
        lint().issues(LongSourceFileDetector.COMPOSE_ISSUE, LongSourceFileDetector.SOURCE_ISSUE)
            .files(
                composable,
                kotlin("src/example/ComposeAtLimit.kt", withLineCount(composeSource("atLimit"), 500)),
                kotlin("src/example/LongCompose.kt", withLineCount(composeSource("longCompose"), 501)),
                kotlin("src/example/PlainAtLimit.kt", withLineCount("package example", 800)),
                kotlin("src/example/LongPlain.kt", withLineCount("package example", 801)),
                kotlin(
                    "src/example/OtherAnnotation.kt",
                    withLineCount("package example\nannotation class Composable\n@Composable fun localAnnotation() = Unit", 501),
                ),
            )
            .allowMissingSdk()
            .testModes(TestMode.DEFAULT)
            .run()
            .expectErrorCount(2)
            .expectContains("File has 501 lines (limit 500). Split it by responsibility. [LongComposeFile]")
            .expectContains("File has 801 lines (limit 800). Split it by responsibility. [LongSourceFile]")
    }

    @Test
    fun givenLongTestSources_whenLintChecksStructure_thenSizeRulesIgnoreThem() {
        lint().issues(
            LongFunctionDetector.ISSUE,
            LongSourceFileDetector.COMPOSE_ISSUE,
            LongSourceFileDetector.SOURCE_ISSUE,
            TestMethodNameDetector.ISSUE,
        )
            .files(
                composable,
                kotlin("test/example/PlainTest.kt", withLineCount("package example\nfun helper() {\n${statements(79)}\n}", 801)),
                kotlin("androidTest/example/ComposeTest.kt", withLineCount(composeSource("testHelper"), 501)),
            )
            .allowMissingSdk()
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    private companion object {
        val composable = kotlin(
            "src/androidx/compose/runtime/Composable.kt",
            "package androidx.compose.runtime\nannotation class Composable",
        )

        fun composeSource(name: String) = """
            package example
            import androidx.compose.runtime.Composable as Ui
            @Ui fun $name() = Unit
        """.trimIndent()

        fun statements(count: Int, indent: String = "    ") = List(count) { "${indent}println($it)" }.joinToString("\n")

        fun withLineCount(source: String, lines: Int) = "$source\n" + "// Padding\n".repeat(lines - source.lines().size)
    }
}
