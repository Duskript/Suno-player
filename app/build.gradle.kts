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
        // v0.1.22-cookie-freshness: versionCode 23. Pure cookie freshness
        // batch: CookieFreshness model (hasSession / expiresAtEpochSeconds /
        // secondsUntilExpiry / isExpired / expiresWithin / status label) with
        // injected-clock helpers; safe WebView cookie adoption via
        // CookieAdoption + CookieRefreshResult so a stored __session is never
        // overwritten by an older/equal (or not-provably-newer) WebView
        // __session, while non-__session WebView cookies ride along on adopt;
        // call sites (download worker pre-sync refresh, cookie refresh worker,
        // library capture) log captured/saved/reason/expiry timestamps only —
        // no cookie or JWT values. Settings status now shows the parsed expiry
        // countdown next to "Configured — not tested". Automatic capture +
        // validate login flow remains a later batch (v0.1.23-auth-refresh-flow).
        versionCode = 23
        versionName = "0.1.22-cookie-freshness"
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
