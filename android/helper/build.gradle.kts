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

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.bigbrother.android.application)
}

android {
    namespace = "li.mercury.bigbrother.catalog"

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "consumer-rules.pro")
        }
    }
}

configurations.consumable("moduleHelper")

// app_process only needs the shrunk dex. Publish it independently of the app's classpath.
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val helperArchive = tasks.register<HelperArchive>("packageHelperArchive") {
        apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
        apkLoader.set(variant.artifacts.getBuiltArtifactsLoader())
        archive.set(layout.buildDirectory.file("outputs/module/helper.apk"))
    }
    artifacts.add("moduleHelper", helperArchive.flatMap(HelperArchive::archive))
}

abstract class HelperArchive : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val apkLoader: Property<BuiltArtifactsLoader>

    @get:OutputFile
    abstract val archive: RegularFileProperty

    @TaskAction
    fun packageDex() {
        val apk = apkLoader.get().load(apkDirectory.get())?.elements?.singleOrNull()
            ?: throw GradleException("Expected one helper release APK")
        ZipFile(apk.outputFile).use { source ->
            val dex = source.entries().asSequence()
                .filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                .singleOrNull()
                ?.takeIf { it.name == "classes.dex" && it.size > 0 }
                ?: throw GradleException("Expected a single classes.dex in the helper release APK")
            ZipOutputStream(archive.get().asFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(dex.name).apply { time = 0 })
                source.getInputStream(dex).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}

// The tests read the protocol cases shared with the daemon's tests, and the
// permission matrix shared with scripts/check-permissions.sh.
val protocolCases = rootDir.resolve("../testdata/protocol")
val permissionMatrix = rootDir.resolve("../testdata/permissions/matrix.tsv")
tasks.withType<Test>().configureEach {
    inputs.dir(protocolCases).withPropertyName("protocolCases").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(permissionMatrix).withPropertyName("permissionMatrix").withPathSensitivity(PathSensitivity.RELATIVE)
}
