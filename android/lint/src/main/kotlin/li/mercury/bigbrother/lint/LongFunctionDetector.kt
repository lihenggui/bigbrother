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
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.UFile

class LongFunctionDetector :
    Detector(),
    SourceCodeScanner {
    override fun getApplicableUastTypes() = listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        if (context.isTestSource || context.isGeneratedSource) return null

        return object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val file = node.sourcePsi as? KtFile ?: return
                val document = file.viewProvider.document ?: return
                // UAST methods omit local functions, so walk the source declarations.
                for (function in PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)) {
                    val start = function.funKeyword?.textOffset ?: continue
                    val end = function.bodyExpression?.textRange?.endOffset ?: continue
                    val lines = document.getLineNumber(end - 1) - document.getLineNumber(start) + 1
                    if (lines > MAX_LINES) {
                        context.report(
                            ISSUE,
                            function,
                            context.getNameLocation(function),
                            "Function spans $lines lines (limit $MAX_LINES). Split it into smaller functions.",
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_LINES = 80

        val ISSUE = Issue.create(
            id = "LongFunction",
            briefDescription = "Kotlin function exceeds $MAX_LINES lines",
            explanation = "Split long functions into named steps so each function has a specific responsibility.",
            category = Category.CORRECTNESS,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(LongFunctionDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
