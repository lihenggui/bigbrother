plugins {
    alias(libs.plugins.bigbrother.android.library)
    alias(libs.plugins.bigbrother.android.hilt)
}

android {
    namespace = "li.mercury.bigbrother.core.common"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
