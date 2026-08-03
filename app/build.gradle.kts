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
        // v0.1.27-android-auto-connection: versionCode 28. Android Auto
        // phone-app media support: SunoPlaybackService is now a Media3
        // MediaLibraryService exposing a driver-safe browse tree (Suno Local →
        // Playlists → saved playlists → playable tracks, local-file-first /
        // streaming-fallback URIs) over the same single shared ExoPlayer and
        // MediaLibrarySession id suno-local-playback that drives the
        // notification, lockscreen, Bluetooth/headset keys, and the v0.1.26
        // home screen widget. Declared via the
        // com.google.android.gms.car.application metadata +
        // res/xml/automotive_app_desc.xml (uses media). No Cars App Library UI
        // and no AAOS-only module in this pass.
        //
        // v0.1.26-home-screen-widget: versionCode 27. Compact home screen
        // playback widget (classic AppWidgetProvider + RemoteViews, no Glance):
        // current track + creator, previous/play-pause/next controls, tap-to-
        // open. Updates ride LocalAudioPlayer.syncStateFromPlayer(); buttons
        // broadcast ACTION_MEDIA_BUTTON key events to the existing single
        // SunoMediaButtonReceiver, so controls route through the same
        // MediaSessionService path as headset/lockscreen keys.
        //
        // v0.1.25-notification-lockscreen-fix: versionCode 26. Hotfix for
        // missing outside-app controls: SunoPlaybackService now registers the
        // shared MediaSession with addSession(), binds Media3's default
        // notification provider to the app playback channel (suno_local_playback),
        // and Settings checks that real channel instead of the old default id.
        versionCode = 28
        versionName = "0.1.27-android-auto-connection"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests {
            // v0.1.27 — SunoMediaLibraryTest builds Media3 MediaItems in plain
            // JVM unit tests; android.net.Uri statics (Uri.parse / fromFile)
            // are stubs in the mockable android.jar, so return default values
            // instead of throwing "Method ... not mocked" when track items
            // parse their URIs. Existing tests call no Android APIs, so this
            // only relaxes the stubs, never changes their assertions.
            isReturnDefaultValues = true
        }
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
