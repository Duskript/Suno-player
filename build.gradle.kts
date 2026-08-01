// Suno Local Player — Top-level build.gradle.kts
// Declares shared plugins via version catalog, applied only in :app module.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
