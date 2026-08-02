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
        // Main screen cleanup: versionCode 20, versionName 0.1.19-main-screen-cleanup.
        // The Library landing page is library-first again: add playlist, backup
        // import/export, and shuffle moved into the top-bar Library actions
        // sheet, the cookie card is more compact, and last-sync/download health
        // collapsed to a one-line strip so playlists/tracks move up the screen.
        versionCode = 20
        versionName = "0.1.19-main-screen-cleanup"
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
