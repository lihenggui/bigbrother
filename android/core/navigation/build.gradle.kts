plugins {
    alias(libs.plugins.bigbrother.android.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "li.mercury.bigbrother.core.navigation"
}

dependencies {
    api(libs.androidx.navigation3.runtime)

    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.lifecycle.viewModel.navigation3)
    implementation(libs.androidx.savedstate.compose)
}
