// A test app, not part of the product: it does on purpose, once each, the
// things Big Brother is meant to notice, so that what gets recorded can be
// compared with what was done (scripts/check-canary.sh).
plugins {
    alias(libs.plugins.bigbrother.android.application)
}

android {
    namespace = "li.mercury.bigbrother.canary"

    defaultConfig {
        applicationId = "li.mercury.bigbrother.canary"
        versionCode = 1
        versionName = "1"
    }
}
