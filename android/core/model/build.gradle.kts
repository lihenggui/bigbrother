plugins {
    alias(libs.plugins.bigbrother.android.library)
}

android {
    namespace = "li.mercury.bigbrother.core.model"
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}
