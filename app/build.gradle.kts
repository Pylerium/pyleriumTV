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

    // Release signing: the key stays off GitHub. Set PYLERIUM_KEYSTORE, PYLERIUM_KEYSTORE_PASSWORD,
    // PYLERIUM_KEY_ALIAS and PYLERIUM_KEY_PASSWORD to build a signed release; without them the release is unsigned.
    signingConfigs {
        create("release") {
            System.getenv("PYLERIUM_KEYSTORE")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("PYLERIUM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PYLERIUM_KEY_ALIAS")
                keyPassword = System.getenv("PYLERIUM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("PYLERIUM_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
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
