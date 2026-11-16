plugins {
    alias(libs.plugins.bigbrother.android.library)
}

android {
    namespace = "li.mercury.bigbrother.hook"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // The Xposed API is provided by the framework at run time.
    compileOnly(libs.libxposed.api)
    implementation(libs.hiddenapibypass)
}
