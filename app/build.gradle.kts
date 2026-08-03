plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.duskript.sunolocal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.duskript.sunolocal"
        minSdk = 26
        targetSdk = 35
        // v0.1.21-durable-media-controls: versionCode 22. Durable media
        // controls batch: next/previous command availability flows from the
        // shared ExoPlayer into the bottom player (buttons disabled, not just
        // dimmed, when the queue cannot step); external controller events
        // (play/pause, timeline changes, position discontinuity, available
        // commands) sync back into LocalAudioPlayer StateFlows and persist the
        // resume snapshot; Settings gains an in-app POST_NOTIFICATIONS request
        // button (Android 13+) alongside the open-settings fallback. Media3
        // default notification provider remains (channel default_channel_id);
        // no custom provider added.
        versionCode = 22
        versionName = "0.1.21-durable-media-controls"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig is required by the Settings > Updates checker so it can read
        // BuildConfig.VERSION_NAME and compare it against the newest GitHub release.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
}
