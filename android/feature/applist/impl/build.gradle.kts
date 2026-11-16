plugins {
    alias(libs.plugins.bigbrother.android.feature.impl)
    alias(libs.plugins.bigbrother.android.library.compose)
}

android {
    namespace = "li.mercury.bigbrother.feature.applist.impl"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.feature.applist.api)
    implementation(projects.feature.appdetail.api)

    implementation(libs.androidx.compose.material3.adaptive.navigation3)
}
