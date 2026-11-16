plugins {
    alias(libs.plugins.bigbrother.android.library)
    alias(libs.plugins.bigbrother.android.library.compose)
}

android {
    namespace = "li.mercury.bigbrother.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material.iconsExtended)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
}
