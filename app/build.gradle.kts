plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.brahmakumaris.trafficcontrol"
    // compileSdk 34 only to access Android 14's full-screen-intent APIs at
    // compile time (NotificationManager.canUseFullScreenIntent(),
    // Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT); targetSdk stays 33
    // so the app doesn't opt into any other Android 14 behavior changes.
    compileSdk = 34

    defaultConfig {
        applicationId = "org.brahmakumaris.trafficcontrol"
        minSdk = 24
        targetSdk = 33
        versionCode = 4
        versionName = "1.2.1"
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

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
}
