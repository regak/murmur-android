plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.pivotstudio.murmur.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.pivotstudio.murmur.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Restrict to arm64-v8a for the sideload build — nearly every phone
        // sold since ~2017 is arm64. Cuts the APK from ~233MB (4 ABIs' worth
        // of sherpa-onnx/onnxruntime .so files) to a size that fits normal
        // file-transfer limits. Drop this filter for a Play-Store multi-ABI
        // release build later.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // sherpa-onnx Kotlin bindings (ASR engine: Moonshine Tiny EN INT8).
    // Not published to Maven Central — shipped as a prebuilt .aar release
    // asset (bundles its own libonnxruntime.so per-ABI, no separate
    // onnxruntime dependency needed). See app/libs/README.md.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    // Coroutines — single-consumer Channel for ordered audio buffer draining
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
