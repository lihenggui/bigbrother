plugins {
    alias(libs.plugins.bigbrother.android.library)
    alias(libs.plugins.bigbrother.android.hilt)
}

android {
    namespace = "li.mercury.bigbrother.core.datastore"
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.dataStore.preferences)

    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)
}
