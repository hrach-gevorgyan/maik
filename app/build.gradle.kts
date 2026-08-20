plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Single source of truth for the version, overridable from CI:
//   ./gradlew assembleRelease -PmaikVersionName=1.2.0 -PmaikVersionCode=5
val maikVersionName: String = (findProperty("maikVersionName") as String?) ?: "1.0.0"
val maikVersionCode: Int = (findProperty("maikVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.maik.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maik.app"
        minSdk = 26
        targetSdk = 35
        versionCode = maikVersionCode
        versionName = maikVersionName

        // MediaPipe ships four ABIs of a ~27 MB native library. Every phone that
        // can hold a 1.7B model is arm64, and dropping the rest halves the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Present only when CI (or you) supplies a keystore; otherwise release builds
    // come out unsigned and the workflow says so plainly.
    val keystorePath = System.getenv("MAIK_KEYSTORE_PATH")
    val hasKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("MAIK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MAIK_KEY_ALIAS")
                keyPassword = System.getenv("MAIK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off: MediaPipe's JNI entry points need keep rules that
            // aren't worth debugging for a sideloaded app.
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
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
        // Off by default in AGP 8, but stated so nobody re-enables them by accident.
        buildConfig = false
        resValues = false
        shaders = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }
}

dependencies {
    // On-device inference. Runs a LiteRT .task bundle locally — no AICore, no cloud.
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
