plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.andri.ailiveavatar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andri.ailiveavatar"
        minSdk = 24
        targetSdk = 35
        versionCode = 27
        versionName = "27.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
}
