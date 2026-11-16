plugins {
    alias(libs.plugins.bigbrother.android.feature.api)
}

android {
    namespace = "li.mercury.bigbrother.feature.appdetail.api"
}

dependencies {
    api(projects.core.model)
}
