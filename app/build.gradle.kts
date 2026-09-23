import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}
val releaseStorePath = localProperties.getProperty("bright.signing.storeFile")

android {
    namespace = "brightnesslock.rongshangs.top"
    compileSdk = 35

    defaultConfig {
        applicationId = "brightnesslock.rongshangs.top"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.6.1"
    }

    signingConfigs {
        create("release") {
            if (!releaseStorePath.isNullOrBlank()) {
                storeFile = file(releaseStorePath)
                storePassword = localProperties.getProperty("bright.signing.storePassword")
                keyAlias = localProperties.getProperty("bright.signing.keyAlias")
                keyPassword = localProperties.getProperty("bright.signing.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (!releaseStorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
