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
        versionCode = 6
        versionName = "1.4"
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

    // Pure-JVM unit tests (app/src/test) - no device/emulator needed.
    testImplementation("junit:junit:4.13.2")

    // On-device instrumented tests (app/src/androidTest) - these exercise
    // the real SharedPreferences-backed classes, which a plain JVM test
    // can't without pulling in Robolectric (avoided here to keep the
    // already version-pinned build toolchain as simple as possible).
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
