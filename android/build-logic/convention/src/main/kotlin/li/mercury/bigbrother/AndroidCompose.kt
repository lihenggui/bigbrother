package li.mercury.bigbrother

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Enables Compose and adds what every Compose module needs. */
internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    commonExtension.buildFeatures.apply {
        compose = true
    }
    dependencies {
        "api"(platform(libs.findLibrary("androidx-compose-bom").get()))
        "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
        "debugImplementation"(libs.findLibrary("androidx-compose-ui-tooling").get())
    }
}
