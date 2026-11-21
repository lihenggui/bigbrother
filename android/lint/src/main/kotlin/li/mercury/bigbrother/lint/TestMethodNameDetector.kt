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

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.UMethod
import java.util.EnumSet

class TestMethodNameDetector :
    Detector(),
    SourceCodeScanner {
    override fun getApplicableUastTypes() = listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        if (!context.isTestSource) return null

        return object : UElementHandler() {
            override fun visitMethod(node: UMethod) {
                val function = node.sourcePsi as? KtNamedFunction ?: return
                val name = function.name ?: return
                if (pattern.matches(name) || node.uAnnotations.none { it.qualifiedName in testAnnotations }) return

                context.report(ISSUE, node, context.getNameLocation(node), MESSAGE)
            }
        }
    }

    companion object {
        private val pattern = Regex("""given[A-Z]\w*_when[A-Z]\w*_then[A-Z]\w*""")
        private val testAnnotations = setOf("org.junit.Test", "kotlin.test.Test")
        private const val MESSAGE = "Name Kotlin tests givenState_whenAction_thenOutcome"

        val ISSUE = Issue.create(
            id = "TestMethodName",
            briefDescription = MESSAGE,
            explanation = "A test name should describe its starting state, the action under test, and the expected outcome.",
            category = Category.TESTING,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(
                TestMethodNameDetector::class.java,
                EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES),
            ),
        )
    }
}
