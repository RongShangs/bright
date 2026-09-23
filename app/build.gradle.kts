plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "brightnesslock.rongshangs.top"
    compileSdk = 35

    defaultConfig {
        applicationId = "brightnesslock.rongshangs.top"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            
            // Disable lint during release build to avoid TLS handshake issues in this environment
            lint {
                checkReleaseBuilds = false
                abortOnError = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
