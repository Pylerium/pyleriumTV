plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cleantv.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pylerium.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.8"

        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.recyclerview)
    implementation(libs.libvlc)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
