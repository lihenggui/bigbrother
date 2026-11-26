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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

tasks.jar {
    manifest.attributes["Lint-Registry-v2"] = "li.mercury.bigbrother.lint.BigBrotherIssueRegistry"
}

// The standalone canary APK is for device tests and ships no translations.
val moduleDirectories = rootProject.subprojects.filter { it.path != ":canary" }
    .map { it.projectDir.relativeTo(rootDir).invariantSeparatorsPath }.sorted()
val stringResources = rootProject.fileTree(rootDir) {
    include(moduleDirectories.map { "$it/src/main/res/values*/strings.xml" })
}
val releaseLocales = providers.gradleProperty("appLocales")
tasks.test {
    inputs.files(stringResources).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("translations.root", rootDir.path)
    systemProperty("translations.modules", moduleDirectories.joinToString(","))
    systemProperty("translations.locales", releaseLocales.get())
    systemProperty("translations.targetSdk", libs.versions.androidTargetSdk.get())
}

dependencies {
    compileOnly(libs.lint.api)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint.tests)
    testImplementation(libs.kotlin.test.junit)
}
