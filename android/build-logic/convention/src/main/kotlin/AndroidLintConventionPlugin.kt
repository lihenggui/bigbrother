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
            val testNamesOnly = providers.gradleProperty("lintTestNamesOnly").isPresent
            when {
                pluginManager.hasPlugin("com.android.application") ->
                    configure<ApplicationExtension> { lint { configure(testNamesOnly) } }

                pluginManager.hasPlugin("com.android.library") ->
                    configure<LibraryExtension> { lint { configure(testNamesOnly) } }

                else -> {
                    pluginManager.apply("com.android.lint")
                    configure<Lint> { configure(testNamesOnly) }
                }
            }
            dependencies.add("lintChecks", project(":lint"))
        }
    }
}

private fun Lint.configure(testNamesOnly: Boolean) {
    // The naming run selects each module directly, including test-only dependencies.
    checkDependencies = !testNamesOnly
    if (testNamesOnly) {
        checkTestSources = true
        checkOnly += "TestMethodName"
    }
    // Dependabot proposes newer versions.
    disable += "GradleDependency"
}
