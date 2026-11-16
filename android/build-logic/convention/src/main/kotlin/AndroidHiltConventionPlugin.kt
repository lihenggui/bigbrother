import li.mercury.bigbrother.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.google.devtools.ksp")
            apply(plugin = "dagger.hilt.android.plugin")
            dependencies {
                "implementation"(libs.findLibrary("hilt.android").get())
                "ksp"(libs.findLibrary("hilt.compiler").get())
                // Lets Hilt's processor read metadata written by this Kotlin version.
                "ksp"(libs.findLibrary("kotlin.metadata").get())
            }
        }
    }
}
