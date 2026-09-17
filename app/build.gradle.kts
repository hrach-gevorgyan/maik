plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Single source of truth for the version, overridable from CI:
//   ./gradlew assembleRelease -PmaikVersionName=1.2.0 -PmaikVersionCode=5
val maikVersionName: String = (findProperty("maikVersionName") as String?) ?: "2.3.0"
val maikVersionCode: Int = (findProperty("maikVersionCode") as String?)?.toInt() ?: 20300

// Set by the release workflow. Keeps emulator-only architectures out of an APK
// that real people will install.
val shipping: Boolean = (findProperty("maikShipping") as String?)?.toBoolean() ?: false

android {
    namespace = "com.maik.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maik.app"
        minSdk = 26
        targetSdk = 35
        versionCode = maikVersionCode
        versionName = maikVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Every phone that can hold a 2B model is arm64; shipping other ABIs of the
        // native runtime only makes the download bigger.
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
        debug {
            // x86_64 as well, so the golden test can run on a CI emulator — but not
            // when a debug build is what actually ships, or the emulator's
            // architecture doubles the size of everyone's download.
            if (!shipping) ndk { abiFilters.add("x86_64") }
        }

        release {
            // R8 is left off: the runtime's JNI entry points need keep rules that
            // aren't worth debugging for a sideloaded app.
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        // Off by default in AGP 8, but stated so nobody re-enables them by accident.
        // The About page shows the version it was built from.
        buildConfig = true
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // On-device inference with LiteRT-LM, Google's current runtime. It applies each
    // model's own chat template and stop tokens, which the MediaPipe runtime did not.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
