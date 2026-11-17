plugins {
    alias(libs.plugins.bigbrother.android.library)
    alias(libs.plugins.bigbrother.android.hilt)
}

android {
    namespace = "li.mercury.bigbrother.core.data"
}

dependencies {
    api(projects.core.common)
    api(projects.core.model)

    implementation(projects.core.datastore)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libsu.core)

    testImplementation(libs.kotlin.test.junit)
}
