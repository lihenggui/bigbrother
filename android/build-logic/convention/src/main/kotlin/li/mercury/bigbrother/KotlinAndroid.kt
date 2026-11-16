package li.mercury.bigbrother

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

/** SDK levels and language level shared by every Android module. */
internal fun configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = 37
        defaultConfig.apply {
            // The module requires a GKI 2.0 kernel, which ships with Android 12.
            minSdk = 31
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
