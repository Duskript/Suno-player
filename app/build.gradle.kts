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
        // v0.1.24-media-player-polish: versionCode 25. Batch E — queue/offline
        // preference polish: playback prefers a verified local file URI
        // (File.exists && length > 0, Uri.fromFile) over the raw path string or
        // network audioUrl; stale/missing/zero-byte local paths fall back to
        // audioUrl when one exists (logged honestly) or are excluded from the
        // queue with a clear "Missing local audio for <title> — resync or
        // re-download this playlist." message instead of a mysterious player
        // error. Resume is never a silent no-op: it explains when the saved
        // track is gone from the library, the saved queue has no playable
        // tracks, or the saved track is unplayable (missing local file, no
        // audioUrl), and the Resume card shows queue size / saved position /
        // unavailable fallback. Batch B external-control sync (listener
        // overrides, mediaId -> track fallback, hasNext/hasPrevious guards) is
        // preserved. No library records or downloaded audio are deleted; no
        // cookies/JWTs are logged.
        versionCode = 25
        versionName = "0.1.24-media-player-polish"
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
