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
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "brightnesslock.rongshangs.top"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "1.8.0"
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
            
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val buildWatchdog by tasks.registering(Exec::class) {
    val source = file("src/main/cpp/watchdog.c")
    val output = file("src/main/assets/watchdog_c")
    inputs.files(source, file("src/main/cpp/watchdog_logic.h"))
    inputs.property("ndkVersion", "28.2.13676358")
    outputs.file(output)
    val sdkPath = localProperties.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
        ?: error("Android SDK path is required")
    val windows = System.getProperty("os.name").startsWith("Windows")
    val host = if (windows) "windows-x86_64" else if (System.getProperty("os.name").contains("Mac")) "darwin-x86_64" else "linux-x86_64"
    val compiler = file("$sdkPath/ndk/28.2.13676358/toolchains/llvm/prebuilt/$host/bin/clang${if (windows) ".exe" else ""}")
    inputs.file(compiler)
    commandLine(compiler.absolutePath, "--target=aarch64-linux-android26", "-static", "-O2", "-Wall", "-Wextra", "-Werror", "-Wl,-s", "-Wl,--build-id=sha1", source.absolutePath, "-o", output.absolutePath)
}
tasks.named("preBuild") { dependsOn(buildWatchdog) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
