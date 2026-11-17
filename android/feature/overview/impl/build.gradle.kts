plugins {
    alias(libs.plugins.bigbrother.android.feature.impl)
    alias(libs.plugins.bigbrother.android.library.compose)
}

android {
    namespace = "li.mercury.bigbrother.feature.overview.impl"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.feature.overview.api)
    implementation(projects.feature.appdetail.api)

    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)

    testImplementation(libs.kotlin.test.junit)
}
