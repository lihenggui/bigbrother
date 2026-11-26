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

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Writes `aapt2 dump badging` of the APK: its package, permissions, features and so on. */
@CacheableTask
abstract class GenerateBadgingTask : DefaultTask() {
    @get:OutputFile
    abstract val badging: RegularFileProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val apk: RegularFileProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val aapt2Executable: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun taskAction() {
        badging.asFile.get().outputStream().use { out ->
            execOperations.exec {
                commandLine(aapt2Executable.get().asFile.absolutePath, "dump", "badging", apk.get().asFile.absolutePath)
                standardOutput = out
            }
        }
    }
}

/**
 * Fails when the APK's badging differs from the one checked in: a new
 * permission, for one, comes in with a reviewed change, never silently with
 * a dependency.
 */
@CacheableTask
abstract class CheckBadgingTask : DefaultTask() {
    // A task with no output always runs; this one is up to date when its inputs are.
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val goldenBadging: RegularFileProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val generatedBadging: RegularFileProperty

    @get:Input
    abstract val updateBadgingTaskName: Property<String>

    override fun getGroup(): String = LifecycleBasePlugin.VERIFICATION_GROUP

    @TaskAction
    fun taskAction() {
        // The first line holds the version, which changes with every release.
        val generated = generatedBadging.get().asFile.readText().comparable()
        val golden = goldenBadging.get().asFile.readText().comparable()
        if (generated != golden) {
            throw GradleException(
                "The APK's badging differs from ${goldenBadging.get().asFile.name}. " +
                    "If the change is intended, run ./gradlew ${updateBadgingTaskName.get()}",
            )
        }
    }

    private fun String.comparable(): String = substringAfter('\n').lines().joinToString("\n") { it.trim() }
}

@CacheableTask
abstract class UpdateBadgingTask : DefaultTask() {
    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFile
    abstract val generatedBadging: RegularFileProperty

    @get:OutputFile
    abstract val goldenBadging: RegularFileProperty

    @TaskAction
    fun taskAction() {
        generatedBadging.get().asFile.copyTo(goldenBadging.get().asFile, overwrite = true)
    }
}

/** Registers generate, check and update badging tasks for each variant, as in Now in Android. */
fun Project.configureBadgingTasks(
    applicationExtension: ApplicationExtension,
    componentsExtension: ApplicationAndroidComponentsExtension,
) {
    componentsExtension.onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.titlecase() }
        val generateBadging = tasks.register<GenerateBadgingTask>("generate${name}Badging") {
            apk.set(variant.artifacts.get(SingleArtifact.APK_FROM_BUNDLE))
            aapt2Executable.set(
                componentsExtension.sdkComponents.sdkDirectory.map { directory ->
                    directory.file(
                        "build-tools/${applicationExtension.buildToolsVersion}/aapt2",
                    )
                },
            )
            badging.set(layout.buildDirectory.file("outputs/apk_from_bundle/${variant.name}/${variant.name}-badging.txt"))
        }
        val updateBadgingTaskName = "update${name}Badging"
        tasks.register<UpdateBadgingTask>(updateBadgingTaskName) {
            generatedBadging.set(generateBadging.flatMap(GenerateBadgingTask::badging))
            goldenBadging.set(layout.projectDirectory.file("${variant.name}-badging.txt"))
        }
        tasks.register<CheckBadgingTask>("check${name}Badging") {
            goldenBadging.set(layout.projectDirectory.file("${variant.name}-badging.txt"))
            generatedBadging.set(generateBadging.flatMap(GenerateBadgingTask::badging))
            this.updateBadgingTaskName.set(updateBadgingTaskName)
            output.set(layout.buildDirectory.dir("intermediates/check${name}Badging"))
        }
    }
}
