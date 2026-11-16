import java.util.Properties

plugins {
    alias(libs.plugins.bigbrother.android.application)
    alias(libs.plugins.bigbrother.android.application.compose)
    alias(libs.plugins.bigbrother.android.hilt)
    alias(libs.plugins.bigbrother.flashable.module)
}

// Release signing is configured in keystore.properties (not in version
// control), with the keys storeFile, storePassword, keyAlias and keyPassword.
val keystoreProperties = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "li.mercury.bigbrother"

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "li.mercury.bigbrother"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Without a release key the build is signed with the debug key,
            // which is enough to install it but differs between machines.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    androidResources {
        // Locales the app is translated into; drives the per-app language picker.
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(projects.feature.applist.api)
    implementation(projects.feature.applist.impl)
    implementation(projects.feature.appdetail.api)
    implementation(projects.feature.appdetail.impl)

    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.navigation)
    implementation(projects.core.ui)

    // The LSPosed hook ships in the same APK.
    implementation(projects.hook)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewModel.navigation3)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3)
}
