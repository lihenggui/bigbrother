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

package li.mercury.bigbrother

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * Formatting with ktlint, and the license header, for an Android module's
 * Kotlin sources, Gradle scripts and XML resources. `spotlessApply` fixes
 * what it can; `spotlessCheck` fails on what is left.
 */
internal fun Project.configureSpotlessForAndroid() {
    configureSpotlessForJvm()
    extensions.configure<SpotlessExtension> {
        format("xml") {
            target("src/**/*.xml")
            // The header goes before the first element, after the XML declaration.
            licenseHeaderFile(rootDir.resolve("spotless/copyright.xml"), "(<[^!?])")
            endWithNewline()
        }
    }
}

internal fun Project.configureSpotlessForJvm() {
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(libs.findVersion("ktlint").get().requiredVersion)
                .editorConfigOverride(mapOf("android" to "true"))
                .customRuleSets(
                    listOf("io.nlopez.compose.rules:ktlint:${libs.findVersion("composeRules").get().requiredVersion}"),
                )
            licenseHeaderFile(rootDir.resolve("spotless/copyright.kt"))
            endWithNewline()
        }
        format("kts") {
            target("*.kts")
            // The header goes before the first line that is neither a comment nor blank.
            licenseHeaderFile(rootDir.resolve("spotless/copyright.kts"), "(^(?![\\/ ]\\*)(?!$).*$)")
            endWithNewline()
        }
    }
}
