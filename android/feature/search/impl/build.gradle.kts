plugins {
    alias(libs.plugins.bigbrother.android.feature.impl)
    alias(libs.plugins.bigbrother.android.library.compose)
}

android {
    namespace = "li.mercury.bigbrother.feature.search.impl"
}

dependencies {
    implementation(projects.core.data)

    implementation(libs.androidx.compose.material3.adaptive)

    testImplementation(libs.kotlin.test.junit)
}
