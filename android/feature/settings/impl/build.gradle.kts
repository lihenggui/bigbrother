plugins {
    alias(libs.plugins.bigbrother.android.feature.impl)
    alias(libs.plugins.bigbrother.android.library.compose)
}

android {
    namespace = "li.mercury.bigbrother.feature.settings.impl"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.feature.settings.api)

    implementation(libs.androidx.core.ktx)
}
