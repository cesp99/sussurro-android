plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Wear OS companion APK. Records audio on the watch and streams it to the
// phone over the Wearable Data Layer; the phone runs whisper.cpp and reports
// state back over MessageClient. We deliberately do NOT bundle the model or
// the JNI library here — the watch never needs to load them, which keeps the
// APK small and avoids targeting wear-specific ABIs for native code.

android {
    namespace = "de.aploi.sussurrobyeyed.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // MUST match the phone module's applicationId. Wear OS treats the
        // watch + phone surfaces as a single app for the Wearable Data
        // Layer: capabilities and channels declared by one are only
        // visible to the other when they share the same
        // (applicationId, signing cert) identity. Diverging here (e.g.
        // appending ".wear") makes `CapabilityClient.getCapability` return
        // empty `nodes` even though dumpsys clearly shows the capability
        // is registered — the lookup is scoped to the caller's app
        // identity, and a different package id is a different identity.
        applicationId = "de.aploi.sussurrobyeyed"
        // Wear OS 4 (Android 13). Older watches are uncommon and not worth
        // the ICompat shim cost.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Wear OS
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.play.services.wearable)

    // Wear OS Tiles: a quick-launch entry on the watch tile carousel so the
    // user can start a Sussurro dictation session without first opening the
    // app. Pulled in alongside ProtoLayout because tile content cannot be
    // expressed in Compose — it has to round-trip through the system tile
    // renderer.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.androidx.wear.protolayout.expression)
    // ListenableFuture helpers — TileService callbacks return Guava's
    // ListenableFuture, but the full Guava android library is enormous;
    // androidx.concurrent ships a tiny `CallbackToFutureAdapter` plus the
    // ResolvableFuture pattern we use to return an already-completed Tile.
    implementation(libs.androidx.concurrent.futures)

    // Compose (BOM aligns transitive versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
