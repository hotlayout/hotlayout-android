plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.binary.compatibility)
}

apiValidation {
    // The demo application is not a library: only `:hotlayout` has a public API to guard.
    ignoredProjects.add("demo")
}
