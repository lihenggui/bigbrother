import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

/**
 * Makes the APK double as the root module (KernelSU, Magisk, APatch).
 *
 * An APK is a zip archive, and a module installer only needs to find the
 * module's files (`module.prop`, the scripts, the daemon) at the root of the
 * archive it is given. Java resources are packaged at the APK root, so the
 * module files are added as generated Java resources.
 *
 * The daemon is packaged once per architecture scripts/build.sh built it for,
 * as `bin/<arch>/bbd`, named like the `$ARCH` the installers set;
 * `module/customize.sh` installs the one for the device as `bin/bbd`.
 *
 * `module/module.prop` is the single source of the version: the app takes its
 * version code and name from it.
 */
class FlashableModuleConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // The Gradle build lives in android/; the module and the daemon
            // are siblings of it in the repository.
            val repository = rootDir.parentFile
            val moduleDir = repository.resolve("module")
            val moduleProp = Properties().apply {
                moduleDir.resolve("module.prop").inputStream().use(::load)
            }

            pluginManager.withPlugin("com.android.application") {
                extensions.configure<ApplicationExtension> {
                    defaultConfig.versionCode = moduleProp.getProperty("versionCode").toInt()
                    defaultConfig.versionName = moduleProp.getProperty("version").removePrefix("v")
                }

                val stageModule = tasks.register<StageModule>("stageModule") {
                    moduleFiles.set(moduleDir)
                    daemons.from(
                        fileTree(repository.resolve("out")) { include("$DAEMON_PREFIX*") },
                    )
                }
                extensions.configure<ApplicationAndroidComponentsExtension> {
                    onVariants { variant ->
                        variant.sources.resources
                            ?.addGeneratedSourceDirectory(stageModule, StageModule::outputDir)
                    }
                }
            }
        }
    }
}

/** scripts/build.sh writes the daemon for each architecture as `out/bbd-<arch>`. */
private const val DAEMON_PREFIX = "bbd-"

/** Collects the module files into one directory for packaging. */
abstract class StageModule : DefaultTask() {
    @get:InputDirectory
    abstract val moduleFiles: DirectoryProperty

    /**
     * The daemons, built by scripts/build.sh before Gradle runs, one per
     * architecture. Their names carry the architecture, so they are inputs
     * by name.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val daemons: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun stage() {
        val binaries = daemons.files.filter { it.isFile }
        if (binaries.isEmpty()) {
            throw GradleException("No daemon in out/. Build it first: ./scripts/build.sh")
        }
        files.sync {
            from(moduleFiles)
            for (binary in binaries) {
                from(binary) {
                    into("bin/${binary.name.removePrefix(DAEMON_PREFIX)}")
                    rename { "bbd" }
                }
            }
            into(outputDir)
        }
    }
}
