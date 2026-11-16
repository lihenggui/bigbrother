import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/**
 * A feature's public surface: its navigation key and the functions other
 * features use to navigate to it. Depending on an `api` module does not pull
 * in the feature's screens.
 */
class AndroidFeatureApiConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "bigbrother.android.library")
            apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
            dependencies {
                "api"(project(":core:navigation"))
            }
        }
    }
}
