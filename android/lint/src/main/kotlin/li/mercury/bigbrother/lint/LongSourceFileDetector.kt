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
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElementOfType

class LongSourceFileDetector :
    Detector(),
    SourceCodeScanner {
    override fun getApplicableUastTypes() = listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        if (context.isTestSource || context.isGeneratedSource) return null

        return object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val file = node.sourcePsi as? KtFile ?: return
                val document = file.viewProvider.document ?: return
                if (document.textLength == 0) return
                val lines = document.getLineNumber(document.textLength - 1) + 1
                if (lines <= MAX_COMPOSE_LINES) return

                val hasComposable = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).any { function ->
                    function.annotationEntries.any {
                        it.toUElementOfType<UAnnotation>()?.qualifiedName == "androidx.compose.runtime.Composable"
                    }
                }
                val issue = if (hasComposable) COMPOSE_ISSUE else SOURCE_ISSUE
                val limit = if (hasComposable) MAX_COMPOSE_LINES else MAX_SOURCE_LINES
                if (lines > limit) {
                    context.report(issue, node, Location.create(context.file), "File has $lines lines (limit $limit). Split it by responsibility.")
                }
            }
        }
    }

    companion object {
        private const val MAX_COMPOSE_LINES = 500
        private const val MAX_SOURCE_LINES = 800
        private val implementation = Implementation(LongSourceFileDetector::class.java, Scope.JAVA_FILE_SCOPE)

        val COMPOSE_ISSUE = Issue.create(
            id = "LongComposeFile",
            briefDescription = "Compose file exceeds $MAX_COMPOSE_LINES lines",
            explanation = "Split files with composable functions by responsibility so their UI components are easier to find and change.",
            category = Category.CORRECTNESS,
            priority = 5,
            severity = Severity.ERROR,
            implementation = implementation,
        )

        val SOURCE_ISSUE = Issue.create(
            id = "LongSourceFile",
            briefDescription = "Kotlin file exceeds $MAX_SOURCE_LINES lines",
            explanation = "Split large Kotlin files by responsibility so related declarations stay together.",
            category = Category.CORRECTNESS,
            priority = 5,
            severity = Severity.ERROR,
            implementation = implementation,
        )
    }
}
