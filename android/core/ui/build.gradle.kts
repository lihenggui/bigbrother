plugins {
    alias(libs.plugins.bigbrother.android.library)
    alias(libs.plugins.bigbrother.android.library.compose)
}

android {
    namespace = "li.mercury.bigbrother.core.ui"
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)

    implementation(libs.androidx.core.ktx)
}
