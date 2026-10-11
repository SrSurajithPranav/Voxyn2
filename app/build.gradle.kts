plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.voxyn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voxyn"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "1.0.23"
        buildConfigField("String", "CLAUDE_API_KEY", "\"\"")
        ndk {
            // The user's device is ARM64; shipping only arm64 keeps the APK small
            // (sherpa-onnx + onnxruntime + Vosk native libs are ~60 MB per ABI).
            abiFilters += listOf("arm64-v8a")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
}

// Keep the debug artifact identifiable when copied to a phone. AGP 8.3 still
// writes its fixed app-debug.apk, so publish a versioned copy after assembly.
tasks.register<Copy>("versionDebugApk") {
    dependsOn("packageDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/versioned-apk/debug"))
    rename { "VOXYN-v${android.defaultConfig.versionName}-build${android.defaultConfig.versionCode}-debug.apk" }
}
tasks.configureEach {
    if (name == "assembleDebug") finalizedBy("versionDebugApk")
}

dependencies {

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)

    implementation(libs.androidx.material3)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)

    // Offline wake-word (Vosk)
    implementation("com.alphacephei:vosk-android:0.3.75")

    // On-device LLM (MediaPipe GenAI) — local conversational AI
    implementation(libs.mediapipe.genai)

    // Command STT (sherpa-onnx + NVIDIA Parakeet-TDT 0.6B v3 INT8)
    implementation(libs.sherpa.onnx)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation("junit:junit:4.13.2")
}
