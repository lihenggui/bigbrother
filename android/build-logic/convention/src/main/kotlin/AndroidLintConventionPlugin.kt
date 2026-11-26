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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLintConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val structureOnly = providers.gradleProperty("lintStructureOnly").isPresent
            val application = pluginManager.hasPlugin("com.android.application")
            val library = pluginManager.hasPlugin("com.android.library")
            when {
                application ->
                    configure<ApplicationExtension> { lint { configure(structureOnly) } }

                library ->
                    configure<LibraryExtension> { lint { configure(structureOnly) } }

                else -> {
                    pluginManager.apply("com.android.lint")
                    configure<Lint> { configure(structureOnly) }
                }
            }
            dependencies.add("lintChecks", project(":lint"))
            if (structureOnly) {
                val lintTask = if (application || library) "lintDebug" else "lint"
                tasks.register("lintStructure") { dependsOn(lintTask) }
            }
        }
    }
}

private fun Lint.configure(structureOnly: Boolean) {
    // The structure run selects each module directly, including test-only dependencies.
    checkDependencies = !structureOnly
    if (structureOnly) {
        checkTestSources = true
        checkOnly += listOf("TestMethodName", "LongFunction", "LongComposeFile", "LongSourceFile")
        checkOnly += translationIssues
    }
    error += translationIssues
    // Dependabot proposes newer versions.
    disable += "GradleDependency"
}

private val translationIssues = listOf(
    "StringFormatMatches",
    "StringFormatInvalid",
    "StringFormatCount",
    "MissingQuantity",
    "UnusedQuantity",
    "ImpliedQuantity",
    "MissingTranslation",
    "ExtraTranslation",
)
