plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.panellog.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.panellog.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-prototype"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
