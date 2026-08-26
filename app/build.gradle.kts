plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freedive.colorapp"
    compileSdk = 36                       // Android 17 preview: doi len 37 khi SDK phat hanh

    defaultConfig {
        applicationId = "com.freedive.colorapp"
        minSdk = 31                       // can ImageReader+HardwareBuffer 10-bit on dinh
        targetSdk = 36
        versionCode = 6
        versionName = "1.1-dev"           // Task S1/S2 — Smart Guide + Skin Lock Mask
        ndk { abiFilters += listOf("arm64-v8a") }   // Pixel 10 Pro Max
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    kotlinOptions { jvmTarget = "17" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")   // Task 5.1 — Material 3
    implementation("androidx.core:core-ktx:1.13.1")
    // Task S1 — SmartGuideManager dung StateFlow (spec yeu cau StateFlow/LiveData)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
