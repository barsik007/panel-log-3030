plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.panellog.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.panellog.app"
        minSdk = 23
        targetSdk = 34
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "0.1.$run"
    }

    // Постоянный ключ подписи: без него каждая сборка на GitHub получала бы
    // новый ключ, и обновление поверх старой версии не устанавливалось бы.
    signingConfigs {
        getByName("debug") {
            storeFile = file("panellog.keystore")
            storePassword = "panellog"
            keyAlias = "panellog"
            keyPassword = "panellog"
        }
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
